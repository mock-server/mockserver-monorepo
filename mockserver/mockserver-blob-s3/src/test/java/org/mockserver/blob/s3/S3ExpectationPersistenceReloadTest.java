package org.mockserver.blob.s3;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.socket.PortFactory;
import org.mockserver.state.StateBackendFactory;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end test that proves cloud (S3) expectation persistence is symmetric:
 * an expectation created over the wire on one MockServer instance is restored
 * and served by a fresh instance pointed at the same bucket after a restart.
 * <p>
 * Backed by a real MinIO instance via Testcontainers. Docker-gated: skips
 * when Docker is unavailable so the suite degrades gracefully on CI agents
 * without a Docker daemon.
 * <p>
 * This is the positive control for the blob-store reload path added to
 * {@code ExpectationFileSystemPersistence}: without that read path the second
 * server starts empty and returns 404, failing this test.
 */
public class S3ExpectationPersistenceReloadTest {

    // quay.io, not Docker Hub: minio/minio is no longer pullable from Docker Hub (the registry
    // returns 401 for every tag, and `docker pull` reports "pull access denied ... repository does
    // not exist"), which broke this suite on master with ContainerFetchException. quay.io is MinIO's
    // other official registry and serves this exact tag - same manifest digest
    // sha256:ac591851803a79aee64bc37f66d77c56b0a4b6e12d9e5356380f4105510f2332 - so the image under
    // test is unchanged.
    private static final String MINIO_IMAGE = "quay.io/minio/minio:RELEASE.2024-11-07T00-52-20Z";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String TEST_BUCKET = "mockserver-reload-test";

    @SuppressWarnings("resource")
    private static GenericContainer<?> minioContainer;
    private static S3Client s3Client;
    private static String endpoint;

    private ClientAndServer server;

    @BeforeClass
    public static void startMinIO() {
        // Wrapped probe (lambda, not method reference): DockerClientFactory.isDockerAvailable()
        // THROWS rather than returning false for post-connection failures, which would turn
        // this skip into a hard ERROR and defeat the assume guard.
        Assume.assumeTrue(
            "Docker is not available -- skipping S3 persistence reload test",
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

        endpoint = "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(9000);

        s3Client = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .forcePathStyle(true)
            .build();

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

    @After
    public void stopServer() {
        stopQuietly(server);
        // Starting a server with blobStoreType("s3") makes StateBackendFactory discover and
        // register the s3 factory in its JVM-global registry. This module runs every test class
        // in ONE reused fork, so leaving that entry behind leaks into whatever runs next — it is
        // what failed S3BlobStoreRegistrarTest's "not registered yet" precondition on a hosted
        // runner while passing on Buildkite, purely on class ordering. Leave the JVM as we found it.
        StateBackendFactory.resetToDefault();
    }

    private Configuration s3PersistenceConfiguration(String persistedExpectationsPath, String keyPrefix) {
        return configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectationsPath)
            .blobStoreType("s3")
            .blobStoreBucket(TEST_BUCKET)
            .blobStoreRegion("us-east-1")
            .blobStoreEndpoint(endpoint)
            .blobStoreKeyPrefix(keyPrefix)
            .blobStoreAccessKeyId(ACCESS_KEY)
            .blobStoreSecretAccessKey(SECRET_KEY)
            // Generous on purpose: the production DEFAULT is 10s, but on a contended CI agent
            // running Docker-in-Docker the cold SDK bootstrap plus the first GetObject can exceed
            // it, and a blown deadline surfaces here as a silent "restored nothing" 404 mismatch
            // rather than as a timeout. The deadline must not be the thing under test.
            .blobStoreRestoreTimeoutSeconds(60);
    }

    @Test
    public void shouldRestoreCloudPersistedExpectationsAfterRestart() throws Exception {
        // A per-run key prefix (without a trailing slash) plus a shared persisted path both
        // servers agree on. The blob key is the FILE NAME of persistedExpectationsPath, and
        // the prefix is joined to it with exactly one separator whichever shape it has -- see
        // shouldRestoreCloudPersistedExpectationsWithATrailingSlashKeyPrefix for the shape
        // that used to compose an invalid '//' object name.
        String keyPrefix = "reload-" + UUID.randomUUID();
        File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
        persistedExpectations.deleteOnExit();
        String persistedExpectationsPath = persistedExpectations.getAbsolutePath();

        // GIVEN a MockServer with S3 persistence, an expectation created over the wire
        server = ClientAndServer.startClientAndServer(
            s3PersistenceConfiguration(persistedExpectationsPath, keyPrefix), PortFactory.findFreePort());
        server
            .when(request().withPath("/persisted"))
            .respond(response().withBody("restored-from-s3"));

        // the expectation serves on the first instance
        assertThat(get(server.getLocalPort(), "/persisted"), is("restored-from-s3"));

        // AND persistence to S3 has completed (the listener write-back is async)
        awaitS3BlobContains(keyPrefix, "/persisted");

        // WHEN the first instance is stopped
        stopQuietly(server);
        server = null;

        // AND a fresh instance is started against the SAME bucket + persisted path
        server = ClientAndServer.startClientAndServer(
            s3PersistenceConfiguration(persistedExpectationsPath, keyPrefix), PortFactory.findFreePort());

        // THEN the expectation is restored and served by the fresh instance
        assertThat(get(server.getLocalPort(), "/persisted"), is("restored-from-s3"));
    }

    @Test
    public void shouldRestoreCloudPersistedExpectationsWithATrailingSlashKeyPrefix() throws Exception {
        // THE REGRESSION: blobStoreKeyPrefix is documented as a folder-style prefix
        // (-Dmockserver.blobStoreKeyPrefix="mockserver/"), and the blob key used to be the
        // ABSOLUTE local persistedExpectationsPath, which begins with '/'. Concatenating the two
        // produced "mockserver//var/folders/.../persistedExpectations.json", which MinIO rejects
        // with HTTP 400 "Object name contains unsupported characters" -- so with the documented
        // prefix shape NOTHING was ever written and nothing could be restored. The key is now the
        // file name, joined to the prefix with exactly one separator.
        String keyPrefix = "reload-trailing-" + UUID.randomUUID() + "/";
        File persistedExpectations = File.createTempFile("persistedExpectationsTrailingSlash", ".json");
        persistedExpectations.deleteOnExit();
        String persistedExpectationsPath = persistedExpectations.getAbsolutePath();

        server = ClientAndServer.startClientAndServer(
            s3PersistenceConfiguration(persistedExpectationsPath, keyPrefix), PortFactory.findFreePort());
        server
            .when(request().withPath("/persisted-trailing-slash"))
            .respond(response().withBody("survives-a-trailing-slash-prefix"));

        assertThat(get(server.getLocalPort(), "/persisted-trailing-slash"), is("survives-a-trailing-slash-prefix"));

        // the write must actually reach S3 -- this is what failed with HTTP 400 before the fix
        awaitS3BlobContains(keyPrefix, "/persisted-trailing-slash");

        // and the object it wrote must be a VALID key: no doubled separator, no embedded local path
        String writtenKey = onlyKeyUnder(keyPrefix);
        assertThat("the object key must carry exactly one separator after the prefix",
            writtenKey, is(keyPrefix + persistedExpectations.getName()));
        assertThat("an object key must never contain a doubled separator, MinIO rejects it: " + writtenKey,
            writtenKey.contains("//"), is(false));

        stopQuietly(server);
        server = null;

        // AND the round trip completes: a fresh instance READS the same key back
        server = ClientAndServer.startClientAndServer(
            s3PersistenceConfiguration(persistedExpectationsPath, keyPrefix), PortFactory.findFreePort());

        assertThat(get(server.getLocalPort(), "/persisted-trailing-slash"), is("survives-a-trailing-slash-prefix"));
    }

    @Test
    public void shouldRestoreCloudPersistedExpectationsWhenInitializationJsonPathMatchesPersistedExpectationsPath() throws Exception {
        // The migration case: a user moving from filesystem persistence to blobStoreType=s3
        // keeps initializationJsonPath pointing at persistedExpectationsPath, which is exactly
        // what the long-standing filesystem guidance tells them to do. The local file stays
        // empty (S3 holds the state), and ExpectationInitializerLoader calls
        // update(EMPTY, new Cause(initializationJsonPath, FILE_INITIALISER)) unconditionally.
        // Cause has value equality, and RequestMatchers.update removes every matcher whose
        // source equals the cause, so a colliding cause source silently deletes everything the
        // restore just loaded.
        String keyPrefix = "reload-init-" + UUID.randomUUID();
        File persistedExpectations = File.createTempFile("persistedExpectationsWithInitializer", ".json");
        persistedExpectations.deleteOnExit();
        String persistedExpectationsPath = persistedExpectations.getAbsolutePath();

        server = ClientAndServer.startClientAndServer(
            s3PersistenceConfiguration(persistedExpectationsPath, keyPrefix), PortFactory.findFreePort());
        server
            .when(request().withPath("/persisted-with-initializer"))
            .respond(response().withBody("survives-the-initializer"));

        assertThat(get(server.getLocalPort(), "/persisted-with-initializer"), is("survives-the-initializer"));
        awaitS3BlobContains(keyPrefix, "/persisted-with-initializer");

        stopQuietly(server);
        server = null;

        // the local file the initializer will read is empty -- only S3 holds the state
        assertThat("the local persisted file must be empty for this test to mean anything",
            persistedExpectations.length(), is(0L));

        server = ClientAndServer.startClientAndServer(
            s3PersistenceConfiguration(persistedExpectationsPath, keyPrefix)
                .initializationJsonPath(persistedExpectationsPath),
            PortFactory.findFreePort());

        assertThat(get(server.getLocalPort(), "/persisted-with-initializer"), is("survives-the-initializer"));
    }

    private String get(int port, String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private String onlyKeyUnder(String keyPrefix) {
        ListObjectsV2Response listing = s3Client.listObjectsV2(b -> b.bucket(TEST_BUCKET).prefix(keyPrefix));
        assertThat("exactly one object expected under " + keyPrefix + " but found " + listing.contents(),
            listing.contents().size(), is(1));
        return listing.contents().get(0).key();
    }

    private void awaitS3BlobContains(String keyPrefix, String marker) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
        while (System.currentTimeMillis() < deadline) {
            ListObjectsV2Response listing = s3Client.listObjectsV2(b -> b.bucket(TEST_BUCKET).prefix(keyPrefix));
            if (listing.contents() != null) {
                for (var object : listing.contents()) {
                    String body = new String(s3Client.getObjectAsBytes(g -> g.bucket(TEST_BUCKET).key(object.key())).asByteArray());
                    if (body.contains(marker)) {
                        return;
                    }
                }
            }
            Thread.sleep(200);
        }
        StringBuilder allKeys = new StringBuilder();
        ListObjectsV2Response everything = s3Client.listObjectsV2(b -> b.bucket(TEST_BUCKET));
        if (everything.contents() != null) {
            everything.contents().forEach(o -> allKeys.append("\n  ").append(o.key()));
        }
        throw new AssertionError("persisted document containing '" + marker + "' never appeared in S3 under prefix " + keyPrefix
            + "; all bucket keys:" + allKeys);
    }
}

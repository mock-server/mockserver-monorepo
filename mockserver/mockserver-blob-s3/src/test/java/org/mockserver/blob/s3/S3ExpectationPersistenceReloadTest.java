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
import org.mockserver.test.TestContainerImages;
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
 * Backed by the {@code adobe/s3mock} S3 API emulator via Testcontainers.
 * Docker-gated: skips when Docker is unavailable so the suite degrades
 * gracefully on CI agents without a Docker daemon.
 * <p>
 * This is the positive control for the blob-store reload path added to
 * {@code ExpectationFileSystemPersistence}: without that read path the second
 * server starts empty and returns 404, failing this test.
 */
public class S3ExpectationPersistenceReloadTest {

    // Must be an S3 API emulator, not a real S3-compatible product: the community MinIO image this
    // suite used before became un-pullable once its registry gated public access, and re-pinning
    // only fights a losing battle. adobe/s3mock is purpose-built, Docker-Hub-published and actively
    // released, matching the emulator approach the GCS and Azure suites already use. Single source of
    // truth: test-container-images.properties, routed through the ECR pull-through cache in CI by
    // TestContainerImages when MOCKSERVER_TEST_IMAGE_REGISTRY is set.
    private static final String S3MOCK_IMAGE = TestContainerImages.S3MOCK;
    // s3mock authenticates nothing but the SDK still requires non-empty credentials to sign.
    private static final String ACCESS_KEY = "s3mock";
    private static final String SECRET_KEY = "s3mock";
    private static final String TEST_BUCKET = "mockserver-reload-test";

    @SuppressWarnings("resource")
    private static GenericContainer<?> s3MockContainer;
    private static S3Client s3Client;
    private static String endpoint;

    private ClientAndServer server;

    @BeforeClass
    public static void startS3Mock() {
        // Wrapped probe (lambda, not method reference): DockerClientFactory.isDockerAvailable()
        // THROWS rather than returning false for post-connection failures, which would turn
        // this skip into a hard ERROR and defeat the assume guard.
        Assume.assumeTrue(
            "Docker is not available -- skipping S3 persistence reload test",
            DockerAvailability.isAvailable(() -> DockerClientFactory.instance().isDockerAvailable())
        );

        // s3mock serves the S3 API over HTTP on 9090 and reports readiness on /favicon.ico
        // (always active, no config, the endpoint Testcontainers and s3mock's own suite use).
        s3MockContainer = new GenericContainer<>(S3MOCK_IMAGE)
            .withExposedPorts(9090)
            .waitingFor(new HttpWaitStrategy()
                .forPath("/favicon.ico")
                .forPort(9090)
                .withStartupTimeout(Duration.ofSeconds(30)));

        s3MockContainer.start();

        endpoint = "http://" + s3MockContainer.getHost() + ":" + s3MockContainer.getMappedPort(9090);

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
    public static void stopS3Mock() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (s3MockContainer != null) {
            s3MockContainer.stop();
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
        // produced "mockserver//var/folders/.../persistedExpectations.json", a malformed key with
        // a doubled separator that strict S3 implementations reject outright -- so with the
        // documented prefix shape NOTHING was ever written and nothing could be restored. The key
        // is now the file name, joined to the prefix with exactly one separator.
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

        // the write must actually reach S3 -- this is what silently wrote nothing before the fix
        awaitS3BlobContains(keyPrefix, "/persisted-trailing-slash");

        // and the object it wrote must be a VALID key: no doubled separator, no embedded local path
        String writtenKey = onlyKeyUnder(keyPrefix);
        assertThat("the object key must carry exactly one separator after the prefix",
            writtenKey, is(keyPrefix + persistedExpectations.getName()));
        assertThat("an object key must never contain a doubled separator, strict S3 rejects it: " + writtenKey,
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

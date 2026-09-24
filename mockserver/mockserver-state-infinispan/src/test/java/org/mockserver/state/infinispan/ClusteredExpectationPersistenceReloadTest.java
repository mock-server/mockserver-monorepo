package org.mockserver.state.infinispan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.socket.PortFactory;
import org.mockserver.state.Blob;
import org.mockserver.state.BlobKeys;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end proof that clustered (Infinispan) expectation persistence is symmetric: an
 * expectation created over the wire on one node is written to the REPL_SYNC blob cache, and
 * a node that STARTS afterwards against the same cluster restores it and MATCHES requests
 * with it.
 * <p>
 * This is the clustered counterpart of {@code S3ExpectationPersistenceReloadTest}. The
 * reload path in {@code ExpectationFileSystemPersistence} runs for every
 * non-{@code FilesystemBlobStore} blob store, and {@code InfinispanStateBackend.blobs()}
 * always returns an {@link InfinispanBlobStore}, so a joining/restarting node restores the
 * fleet's shared expectation document.
 * <p>
 * <b>What is actually new here.</b> That reload path is already covered elsewhere:
 * {@code ExpectationBlobStoreRestoreTest} in {@code mockserver-core} exercises it at unit
 * level against an {@code InMemoryBlobStore}, constructing {@code ExpectationFileSystemPersistence}
 * directly with no server and no cluster (including the {@code blobStoreRestoreTimeoutSeconds=0}
 * skip that the negative control below re-checks in situ), and
 * {@code S3ExpectationPersistenceReloadTest} exercises it end-to-end against an S3 emulator behind a
 * Docker gate. Neither can show what this test adds: that a clustered node's
 * {@link InfinispanBlobStore} is the store {@code HttpState} hands to the restore, and that a
 * real restarted member of a live cluster recovers the fleet's shared expectations.
 * <p>
 * <b>Shape of the scenario</b> — a real node restart inside a live fleet:
 * <ol>
 *   <li>a bare {@link InfinispanStateBackend} ("fleet keeper") joins the cluster and stays
 *       up for the whole test, so the replicated caches survive the restart;</li>
 *   <li>node A starts as a full MockServer with {@code stateBackend=infinispan},
 *       {@code clusterEnabled=true} and {@code persistExpectations=true}, and an expectation
 *       is created on it over the wire;</li>
 *   <li>node A is stopped completely (its cache manager leaves the cluster);</li>
 *   <li>node B starts fresh against the same cluster and the same
 *       {@code persistedExpectationsPath}, and must serve the expectation.</li>
 * </ol>
 * Node A is fully stopped BEFORE node B starts so that no in-flight replication event can
 * reconcile node B's matcher cache behind the reload path's back — the restore in node B's
 * {@code HttpState} constructor is the only route by which the expectation can reach it.
 * <p>
 * The local {@code persistedExpectationsPath} file is asserted to be EMPTY before node B
 * starts, so this can never be passing via the filesystem-initializer route: only the
 * clustered blob cache holds the state.
 * <p>
 * <b>No Docker, no external service</b> — this mirrors {@link ClusteredTwoNodeTest}: the
 * cluster is formed in-JVM over the built-in JGroups loopback stack, so it runs as an
 * ordinary unit test.
 *
 * @see ClusteredTwoNodeTest
 */
@Timeout(180) // safety net: kill the test if cluster formation or a server start hangs
class ClusteredExpectationPersistenceReloadTest {

    private static final Duration CLUSTER_FORMATION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration BLOB_REPLICATION_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Generous on purpose: the production DEFAULT is 10s, but a blown deadline surfaces here as
     * a silent "restored nothing" 404 rather than as a timeout, so the deadline must not be the
     * thing under test.
     */
    private static final int RESTORE_TIMEOUT_SECONDS = 60;

    /**
     * Unique per test method: a static cluster name reused across setUp/tearDown cycles races
     * JGroups SHARED_LOOPBACK channel teardown and makes sibling tests flaky (see
     * {@link ClusteredTwoNodeTest}).
     */
    private String clusterName;
    private InfinispanStateBackend fleetKeeper;
    private ClientAndServer server;
    private File persistedExpectations;
    private String blobKey;

    @BeforeEach
    void setUp() throws Exception {
        clusterName = "mockserver-reload-cluster-" + System.nanoTime();

        persistedExpectations = File.createTempFile("clusteredPersistedExpectations", ".json");
        persistedExpectations.deleteOnExit();
        // Both nodes must be given the identical persistedExpectationsPath (an absolute local
        // path). The blob-store KEY under which the document lands is a separate thing: for a
        // non-filesystem store ExpectationFileSystemPersistence keys by the file NAME alone
        // (see BlobKeys.forPersistedFile), not the absolute path.
        blobKey = persistedExpectations.getAbsolutePath();

        // The rest of the fleet: holds the REPL_SYNC caches while the MockServer node restarts.
        fleetKeeper = new InfinispanStateBackend(clusteredConfiguration());
    }

    @AfterEach
    void tearDown() {
        // FIRST, so it cannot be skipped by anything below throwing: leave the JVM-wide
        // StateBackendFactory as it was found. HttpState's auto-discovery registers the
        // Infinispan factory globally, which would otherwise make every later HttpState in
        // this fork clustered - and surface as an unrelated failure in
        // InfinispanStateBackendRegistrarTest, which asserts no custom factory is registered.
        // Deregistering the factory cannot affect the already-constructed backends stopped below.
        InfinispanStateBackendRegistrar.deregister();
        stopQuietly(server);
        server = null;
        if (fleetKeeper != null) {
            fleetKeeper.close();
            fleetKeeper = null;
        }
        if (persistedExpectations != null) {
            //noinspection ResultOfMethodCallIgnored
            persistedExpectations.delete();
        }
    }

    @Test
    void shouldRestoreClusterPersistedExpectationsOnANodeThatStartsAfterwards() throws Exception {
        // GIVEN a MockServer node in the cluster with clustered persistence enabled
        server = startNode(RESTORE_TIMEOUT_SECONDS);
        awaitClusterSize(2);

        // AND an expectation created on it over the wire
        server
            .when(request().withPath("/persisted"))
            .respond(response().withBody("restored-from-cluster"));
        assertThat("the expectation must serve on the original node",
            get(server.getLocalPort(), "/persisted"), is("restored-from-cluster"));

        // AND the persisted document has replicated to the clustered blob cache
        awaitBlobContains("/persisted");

        // WHEN that node is stopped completely and a FRESH node starts against the same cluster
        stopQuietly(server);
        server = null;
        assertThat("the local persisted file must be empty for this test to mean anything - "
                + "only the clustered blob cache may hold the state",
            persistedExpectations.length(), is(0L));

        server = startNode(RESTORE_TIMEOUT_SECONDS);

        // THEN the fresh node restored the expectation and MATCHES the request with it
        assertThat(get(server.getLocalPort(), "/persisted"), is("restored-from-cluster"));
    }

    /**
     * Negative control, and the permanent guard against this test going hollow: with
     * {@code blobStoreRestoreTimeoutSeconds=0} the production code documents that the restore
     * is skipped entirely. If the fresh node could obtain the expectation by ANY other route
     * (JGroups state transfer of the expectations cache, a stray invalidation event, the local
     * file) this would still serve the body — and the positive test above would prove nothing.
     */
    @Test
    void shouldNotRestoreClusterPersistedExpectationsWhenTheBlobStoreRestoreIsDisabled() throws Exception {
        server = startNode(RESTORE_TIMEOUT_SECONDS);
        awaitClusterSize(2);

        server
            .when(request().withPath("/persisted"))
            .respond(response().withBody("restored-from-cluster"));
        assertThat(get(server.getLocalPort(), "/persisted"), is("restored-from-cluster"));
        awaitBlobContains("/persisted");

        stopQuietly(server);
        server = null;

        // restore disabled => nothing is read back, even though the cluster still holds the blob
        server = startNode(0);

        assertThat("with the blob-store restore disabled the fresh node must NOT have the expectation",
            get(server.getLocalPort(), "/persisted"), is(""));
        assertThat("the blob is still in the cluster - only the restore was skipped",
            blobBody().contains("/persisted"), is(true));
    }

    // --- helpers ---

    private ClientAndServer startNode(int blobStoreRestoreTimeoutSeconds) {
        return ClientAndServer.startClientAndServer(
            clusteredConfiguration()
                .persistExpectations(true)
                .persistedExpectationsPath(blobKey)
                .blobStoreRestoreTimeoutSeconds(blobStoreRestoreTimeoutSeconds),
            PortFactory.findFreePort());
    }

    private Configuration clusteredConfiguration() {
        return configuration()
            .stateBackend("infinispan")
            .clusterEnabled(true)
            .clusterName(clusterName);
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

    private void awaitClusterSize(int expectedSize) {
        long deadline = System.currentTimeMillis() + CLUSTER_FORMATION_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (fleetKeeper.getCacheManager().getTransport().getMembers().size() >= expectedSize) {
                return;
            }
            sleep();
        }
        fail("cluster did not reach size " + expectedSize + " within " + CLUSTER_FORMATION_TIMEOUT
            + "; current size=" + fleetKeeper.getCacheManager().getTransport().getMembers().size());
    }

    /**
     * Waits for the persisted document to appear in the CLUSTERED blob cache, read through the
     * fleet keeper's backend rather than the writing node's, so this also proves the write
     * actually replicated. The write-back is dispatched asynchronously by the matcher listener,
     * hence the poll.
     */
    private void awaitBlobContains(String marker) {
        long deadline = System.currentTimeMillis() + BLOB_REPLICATION_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (blobBody().contains(marker)) {
                return;
            }
            sleep();
        }
        fail("persisted document containing '" + marker + "' never appeared in the clustered blob"
            + " store under key " + storeKey() + "; current content: '" + blobBody() + "'");
    }

    private String storeKey() {
        // The key production actually writes under: file name for a non-filesystem store.
        return BlobKeys.forPersistedFile(fleetKeeper.blobs(), persistedExpectations.toPath());
    }

    private String blobBody() {
        Optional<Blob> blob = fleetKeeper.blobs().get(storeKey());
        return blob.map(value -> new String(value.getData(), StandardCharsets.UTF_8)).orElse("");
    }

    private void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while polling", interrupted);
        }
    }
}

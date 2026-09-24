package org.mockserver.state;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.uuid.UUIDService;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default in-memory {@link StateBackend} that wraps today's exact data
 * structures for zero behaviour change. Every store is node-local;
 * there is no network I/O or clustering in this implementation.
 */
public class InMemoryStateBackend implements StateBackend {

    private final InMemoryExpectationKeyValueStore expectations;
    private final InMemoryKeyValueStore<String> scenarioStates;
    private final InMemoryKeyValueStore<Integer> sharedTimesCounters;
    private final ConcurrentHashMap<String, KeyValueStore<ObjectNode>> crudStores;
    private final BlobStore blobStore;
    private final String nodeId;
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();

    public InMemoryStateBackend(int maxExpectations) {
        this(maxExpectations, 0L, new InMemoryBlobStore());
    }

    public InMemoryStateBackend(int maxExpectations, long maxExpectationsSizeInBytes) {
        this(maxExpectations, maxExpectationsSizeInBytes, new InMemoryBlobStore());
    }

    public InMemoryStateBackend(int maxExpectations, BlobStore blobStore) {
        this(maxExpectations, 0L, blobStore);
    }

    /**
     * Creates an in-memory state backend with an externally-supplied
     * {@link BlobStore}. This allows the {@link StateBackendFactory} to
     * inject a {@link FilesystemBlobStore} when {@code blobStoreType=filesystem}
     * while keeping the KV stores in-memory.
     *
     * @param maxExpectations            maximum number of expectations (count bound)
     * @param maxExpectationsSizeInBytes byte budget for stored expectations (0 disables the byte bound)
     * @param blobStore                  the blob store implementation to use
     */
    public InMemoryStateBackend(int maxExpectations, long maxExpectationsSizeInBytes, BlobStore blobStore) {
        this.expectations = new InMemoryExpectationKeyValueStore(maxExpectations, maxExpectationsSizeInBytes);
        this.scenarioStates = new InMemoryKeyValueStore<>();
        this.sharedTimesCounters = new InMemoryKeyValueStore<>();
        this.crudStores = new ConcurrentHashMap<>();
        this.blobStore = blobStore;
        this.nodeId = UUIDService.getUUID();
    }

    @Override
    public KeyValueStore<ExpectationEntry> expectations() {
        return expectations;
    }

    /**
     * Returns the expectation store cast to its concrete type, so
     * callers that need the sorted-list or queue API can access it.
     */
    public InMemoryExpectationKeyValueStore expectationStore() {
        return expectations;
    }

    @Override
    public KeyValueStore<String> scenarioStates() {
        return scenarioStates;
    }

    @Override
    public KeyValueStore<Integer> sharedTimesCounters() {
        return sharedTimesCounters;
    }

    @Override
    public KeyValueStore<ObjectNode> crudEntities(String namespace) {
        return crudStores.computeIfAbsent(namespace, ns -> new InMemoryKeyValueStore<>());
    }

    @Override
    public BlobStore blobs() {
        return blobStore;
    }

    @Override
    public void addInvalidationListener(InvalidationListener listener) {
        listeners.add(listener);
        expectations.addInvalidationListener(listener);
        scenarioStates.addInvalidationListener(listener);
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public void close() {
        // Close the blob store to release any cloud SDK resources
        // (connection pools, threads). No-op for InMemoryBlobStore
        // and FilesystemBlobStore.
        if (blobStore != null) {
            try {
                blobStore.close();
            } catch (Exception e) {
                // best-effort: log and continue shutdown
            }
        }
    }
}

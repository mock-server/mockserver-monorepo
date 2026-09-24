package org.mockserver.state;

import org.mockserver.collections.CircularPriorityQueue;
import org.mockserver.mock.SortableExpectationId;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.mockserver.mock.SortableExpectationId.EXPECTATION_SORTABLE_PRIORITY_COMPARATOR;
import static org.mockserver.mock.SortableExpectationId.NULL;

/**
 * In-memory {@link KeyValueStore} for expectations, backed by a
 * {@link CircularPriorityQueue} that provides identical ordering and
 * insertion-order eviction to today's {@code RequestMatchers} internals.
 * <p>
 * This is the ONLY expectation KV implementation for phase 2a. It wraps
 * the exact same data structures so behaviour is byte-for-byte unchanged.
 */
public class InMemoryExpectationKeyValueStore implements KeyValueStore<ExpectationEntry> {

    private final CircularPriorityQueue<String, ExpectationEntry, SortableExpectationId> queue;
    private final ConcurrentHashMap<String, AtomicLong> versions = new ConcurrentHashMap<>();
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();

    public InMemoryExpectationKeyValueStore(int maxSize) {
        this(maxSize, 0L);
    }

    public InMemoryExpectationKeyValueStore(int maxSize, long maxBytes) {
        this.queue = new CircularPriorityQueue<>(
            maxSize,
            maxBytes,
            entry -> entry != null && entry.getExpectation() != null ? entry.getExpectation().estimatedHeapSize() : 0L,
            EXPECTATION_SORTABLE_PRIORITY_COMPARATOR,
            entry -> entry != null
                ? new SortableExpectationId(entry.getId(), entry.getPriority(), entry.getCreated())
                : NULL,
            entry -> entry != null ? entry.getId() : ""
        );
        // Clean up the versions entry for any expectation evicted by overflow
        // past maxSize. Without this, overflow-evicted keys would leak their
        // AtomicLong version entry forever (the versions map is otherwise only
        // pruned by remove/compareAndRemove/clear, never by eviction).
        this.queue.setEvictionListener(evicted -> {
            if (evicted != null) {
                versions.remove(evicted.getId());
            }
        });
    }

    /**
     * Exposes the underlying queue so that {@code RequestMatchers} can
     * use its iteration/sorting without re-implementing it.
     */
    public CircularPriorityQueue<String, ExpectationEntry, SortableExpectationId> getQueue() {
        return queue;
    }

    @Override
    public Optional<Versioned<ExpectationEntry>> get(String key) {
        return queue.getByKey(key).map(entry -> {
            AtomicLong ver = versions.get(key);
            long v = ver != null ? ver.get() : 1L;
            return new Versioned<>(entry, v);
        });
    }

    @Override
    public long put(String key, ExpectationEntry value) {
        // If the key already exists, replace the value IN PLACE to preserve
        // insertion-order position (and therefore eviction order).  This
        // mirrors how the pre-Phase-2b RequestMatchers did in-place
        // HttpRequestMatcher.update() — the eviction victim when over
        // maxExpectations is always the oldest by original insertion time,
        // regardless of subsequent updates. (COR-01 fix)
        if (!queue.replaceValue(key, value)) {
            // New key — add normally (may trigger eviction if at capacity)
            queue.add(value);
        }
        AtomicLong ver = versions.computeIfAbsent(key, k -> new AtomicLong(0));
        long newVersion = ver.incrementAndGet();
        fireChanged(key);
        return newVersion;
    }

    @Override
    public Optional<Versioned<ExpectationEntry>> putIfAbsent(String key, ExpectationEntry value) {
        Optional<Versioned<ExpectationEntry>> existing = get(key);
        if (existing.isPresent()) {
            return existing;
        }
        // Key does not exist — add it
        queue.add(value);
        AtomicLong ver = versions.computeIfAbsent(key, k -> new AtomicLong(0));
        ver.incrementAndGet();
        fireChanged(key);
        return Optional.empty();
    }

    @Override
    public boolean compareAndSet(String key, long expectedVersion, ExpectationEntry value) {
        AtomicLong ver = versions.get(key);
        if (ver == null) {
            return false;
        }
        synchronized (ver) {
            if (ver.get() != expectedVersion) {
                return false;
            }
            // Preserve insertion-order position on update (COR-01)
            if (!queue.replaceValue(key, value)) {
                queue.add(value);
            }
            ver.incrementAndGet();
        }
        fireChanged(key);
        return true;
    }

    @Override
    public boolean compareAndRemove(String key, long expectedVersion) {
        AtomicLong ver = versions.get(key);
        if (ver == null) {
            return false;
        }
        synchronized (ver) {
            if (ver.get() != expectedVersion) {
                return false;
            }
            queue.getByKey(key).ifPresent(existing -> {
                queue.remove(existing);
            });
            versions.remove(key);
        }
        fireChanged(key);
        return true;
    }

    @Override
    public boolean remove(String key) {
        return queue.getByKey(key).map(existing -> {
            boolean removed = queue.remove(existing);
            versions.remove(key);
            if (removed) {
                fireChanged(key);
            }
            return removed;
        }).orElse(false);
    }

    @Override
    public Stream<Entry<ExpectationEntry>> entries() {
        return queue.stream().map(entry -> {
            AtomicLong ver = versions.get(entry.getId());
            long v = ver != null ? ver.get() : 1L;
            return new Entry<>(entry.getId(), new Versioned<>(entry, v));
        });
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public void clear() {
        // Remove all entries from the queue. queue.remove() is an explicit
        // removal, so it does NOT fire the eviction listener (which only
        // handles overflow eviction) — versions.clear() below is the bulk
        // cleanup that prunes every version entry in one shot.
        queue.stream().collect(java.util.stream.Collectors.toList())
            .forEach(queue::remove);
        versions.clear();
        fireCleared();
    }

    @Override
    public void addInvalidationListener(InvalidationListener listener) {
        listeners.add(listener);
    }

    /**
     * Returns the sorted list of entries, in the same order as today's
     * {@code CircularPriorityQueue.toSortedList()}.
     */
    public java.util.List<ExpectationEntry> toSortedList() {
        return queue.toSortedList();
    }

    /**
     * Update the max size (e.g. when maxExpectations changes at runtime). A shrink evicts the
     * eldest expectations immediately, firing the versions-map cleanup listener registered in the
     * constructor.
     */
    @Override
    public void setMaxSize(int maxSize) {
        queue.setMaxSize(maxSize);
    }

    @Override
    public void setMaxBytes(long maxBytes) {
        queue.setMaxBytes(maxBytes);
    }

    @Override
    public long getByteEvictedCount() {
        return queue.getByteEvictedCount();
    }

    @Override
    public long getTotalBytes() {
        return queue.getTotalBytes();
    }

    @Override
    public long getMaxBytes() {
        return queue.getMaxBytes();
    }

    private void fireChanged(String key) {
        for (InvalidationListener listener : listeners) {
            listener.onChanged(key);
        }
    }

    private void fireCleared() {
        for (InvalidationListener listener : listeners) {
            listener.onCleared();
        }
    }
}

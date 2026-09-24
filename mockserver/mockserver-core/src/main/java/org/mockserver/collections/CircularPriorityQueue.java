package org.mockserver.collections;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Priority-ordered queue with insertion-order eviction past {@code maxSize}.
 * <p>
 * <b>Concurrency contract:</b> all mutating methods ({@link #add(Object)},
 * {@link #remove(Object)}, {@link #replaceValue(Object, Object)},
 * {@link #addPriorityKey(Object)}, {@link #removePriorityKey(Object)},
 * {@link #setMaxSize(int)} and {@link #setEvictionListener(java.util.function.Consumer)})
 * are <b>single-writer</b> — callers serialize them on the control plane (the
 * Netty event loop / per-store synchronization). Read methods
 * ({@link #stream()}, {@link #toSortedList()}, {@link #getByKey(Object)},
 * {@link #size()}, {@link #keyMap()}) may run concurrently with the single
 * writer and are eventually consistent: a read concurrent with an in-flight
 * mutation may not yet reflect it, but never returns nulls (the
 * {@code filter(nonNull)} guard) or corrupt state.
 * <p>
 * <b>Precondition:</b> {@link #add(Object)} must not be called for a key that
 * already exists in the queue — use {@link #replaceValue(Object, Object)} for
 * in-place updates. Adding a duplicate key would push the key twice into the
 * insertion-order queue and corrupt eviction accounting.
 *
 * @author jamesdbloom
 */
public class CircularPriorityQueue<K, V, SLK extends Keyed<K>> {
    private int maxSize;
    // Optional byte budget (max bytes + weigher), like CircularConcurrentLinkedDeque. Disabled when
    // maxBytes <= 0 or weigher == null. volatile for lock-free live resize via setMaxBytes.
    private volatile long maxBytes;
    // Must return the same weight for the same value throughout its life, or the running total corrupts.
    private final ToLongFunction<V> weigher;
    private final Function<V, SLK> skipListKeyFunction;
    private final Function<V, K> mapKeyFunction;
    private final ConcurrentSkipListSet<SLK> sortOrderSkipList;
    // Insertion-order queue holds KEYS, not values. Insertion order only
    // matters for eviction (poll the eldest on overflow) and a key never
    // changes on an in-place update, so storing keys lets replaceValue run
    // in O(log n) (byKey.put + skip-list swap) instead of rebuilding the
    // whole queue. The live value for a key is always resolved via byKey.
    // Tradeoff: an element's eviction slot is fixed by its first insertion;
    // an in-place replaceValue leaves that slot untouched (which is the
    // desired semantics — eviction order follows original insertion time).
    private final ConcurrentLinkedQueue<K> insertionOrderQueue = new ConcurrentLinkedQueue<>();
    // Explicit O(1) element count for insertionOrderQueue. ConcurrentLinkedQueue.size()
    // is documented as an O(n) traversal ("NOT a constant-time operation"), and evictExcess()
    // evaluates it on EVERY add() — even when nothing is evicted — which made bulk expectation
    // loading O(n^2) (a 10k-expectation initializer paid a ~50M-node walk in aggregate). This
    // counter is mutated only under the single-writer contract (add / evictExcess / remove),
    // read lock-free by size(), and kept exactly in step with insertionOrderQueue.
    private final AtomicInteger queueSize = new AtomicInteger(0);
    // Running weight total, kept in step with queueSize for an O(1) budget check.
    private final AtomicLong totalBytes = new AtomicLong(0);
    // Overflow evictions driven by the byte budget rather than the count bound; lets a caller name the
    // right property in a warn-once.
    private final AtomicLong byteEvictedCount = new AtomicLong(0);
    private final ConcurrentMap<K, V> byKey = new ConcurrentHashMap<>();
    // Cached snapshot of the sorted list; nulled on every mutation so toSortedList()
    // rebuilds lazily. volatile ensures the null write is visible to all threads
    // immediately (no stale-cache reads after an add/remove).
    private volatile List<V> sortedCache = null;
    // Invoked once for every element evicted by overflow past maxSize, AFTER the
    // element has been removed from all three backing structures. Default is a
    // no-op so existing users/tests are unaffected. Used to clean up satellite
    // state keyed by the evicted element (e.g. a versions map) that would
    // otherwise leak for overflow-evicted keys.
    private volatile Consumer<V> evictionListener = element -> {
    };
    // Invoked for every structural mutation so a derived index (e.g. RequestMatchers'
    // CandidateIndex) can be maintained INCREMENTALLY instead of rebuilt on read. Fired
    // under the single-writer contract from add / remove / addPriorityKey / removePriorityKey /
    // replaceValue and overflow eviction, so the derived index sees exactly the same element
    // add/remove events the queue applies. Default is a no-op so existing users are unaffected.
    private volatile MutationListener<V> mutationListener = noOpMutationListener();

    @SuppressWarnings("unchecked")
    private static <V> MutationListener<V> noOpMutationListener() {
        return (MutationListener<V>) NO_OP_MUTATION_LISTENER;
    }

    /**
     * Listener notified of every structural add/remove so a derived structure can be maintained
     * incrementally. {@link #onAdd(Object)} fires when an element enters (or re-enters, on an
     * {@link #addPriorityKey(Object)} priority re-key or a {@link #replaceValue(Object, Object)});
     * {@link #onRemove(Object)} fires when it leaves (an explicit remove, a
     * {@link #removePriorityKey(Object)}, an overflow eviction or the old value of a replace).
     * Fired under the single-writer contract; implementations must be cheap and non-blocking.
     */
    public interface MutationListener<V> {
        void onAdd(V element);

        void onRemove(V element);
    }

    private static final MutationListener<Object> NO_OP_MUTATION_LISTENER = new MutationListener<Object>() {
        @Override
        public void onAdd(Object element) {
        }

        @Override
        public void onRemove(Object element) {
        }
    };

    public CircularPriorityQueue(int maxSize, Comparator<? super SLK> skipListComparator, Function<V, SLK> skipListKeyFunction, Function<V, K> mapKeyFunction) {
        this(maxSize, 0L, null, skipListComparator, skipListKeyFunction, mapKeyFunction);
    }

    /**
     * Byte-budget-aware constructor: an add/replace that would push the running weight over
     * {@code maxBytes} evicts the eldest elements until it fits (never emptying the queue — a single
     * over-budget element is kept). Disabled when {@code maxBytes <= 0} or {@code weigher == null}.
     */
    public CircularPriorityQueue(int maxSize, long maxBytes, ToLongFunction<V> weigher, Comparator<? super SLK> skipListComparator, Function<V, SLK> skipListKeyFunction, Function<V, K> mapKeyFunction) {
        sortOrderSkipList = new ConcurrentSkipListSet<>(skipListComparator);
        this.maxSize = maxSize;
        this.maxBytes = maxBytes;
        this.weigher = weigher;
        this.skipListKeyFunction = skipListKeyFunction;
        this.mapKeyFunction = mapKeyFunction;
    }

    /**
     * Registers a listener invoked once for every element evicted because the
     * queue grew past {@code maxSize}. The listener is called AFTER the element
     * has been removed from the insertion queue, sort skip-list and byKey map,
     * so satellite state can be cleaned up safely. It is NOT invoked on explicit
     * {@link #remove(Object)} or {@link #replaceValue(Object, Object)}.
     *
     * @param evictionListener the listener, or {@code null} to restore the no-op default
     */
    public void setEvictionListener(Consumer<V> evictionListener) {
        this.evictionListener = evictionListener != null ? evictionListener : element -> {
        };
    }

    /**
     * Registers a listener notified of every structural add/remove so a derived structure (e.g.
     * a candidate index) can be maintained incrementally rather than rebuilt on read. Fired under
     * the single-writer contract from every mutating method (including overflow eviction); the
     * listener must be cheap and non-blocking. Passing {@code null} restores the no-op default.
     *
     * @param mutationListener the listener, or {@code null} to restore the no-op default
     */
    public void setMutationListener(MutationListener<V> mutationListener) {
        this.mutationListener = mutationListener != null ? mutationListener : noOpMutationListener();
    }

    /**
     * Resize the bound. A SHRINK takes effect immediately: the eldest elements are evicted (firing
     * the eviction listener, exactly as an overflow eviction would) until the queue fits the new
     * bound, rather than waiting for the next {@link #add(Object)}. This is what makes a live
     * {@code maxExpectations} change via {@code PUT /mockserver/configuration} take effect at once.
     * Subject to the single-writer contract in the class javadoc.
     */
    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
        if (maxSize > 0) {
            evictExcess();
        }
        sortedCache = null;
    }

    /**
     * Resize the byte budget; a shrink evicts the eldest elements immediately until it fits (never
     * emptying the queue). No-op when the budget is disabled.
     */
    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
        evictExcessBytes();
        sortedCache = null;
    }

    /**
     * Evict the eldest elements until the queue fits {@code maxSize}. Shared by {@link #add(Object)}
     * and {@link #setMaxSize(int)} so both paths keep the byKey map, sort skip-list and insertion
     * queue consistent and fire the eviction listener identically.
     */
    private void evictExcess() {
        // queueSize.get() is O(1) — using insertionOrderQueue.size() here (an O(n) walk) made
        // this loop O(n) on every add() and therefore bulk loading O(n^2).
        while (queueSize.get() > maxSize) {
            K keyToRemove = insertionOrderQueue.poll();
            if (keyToRemove == null) {
                // Queue emptied out from under the counter — cannot happen under the single-writer
                // contract, but bail rather than spin so a precondition violation can never hang.
                queueSize.set(insertionOrderQueue.size());
                break;
            }
            queueSize.decrementAndGet();
            // Resolve the live value via byKey, remove it, then update the
            // skip-list and fire the eviction listener. Under the single-
            // writer contract byKey.remove is non-null here (the key was
            // just polled from the insertion queue and no concurrent writer
            // exists); the guard defends against a precondition violation
            // (duplicate-key add) so eviction never NPEs or double-strips
            // the skip-list / fires the listener for a missing element.
            V elementToRemove = byKey.remove(keyToRemove);
            if (elementToRemove != null) {
                subtractWeight(elementToRemove);
                sortOrderSkipList.remove(skipListKeyFunction.apply(elementToRemove));
                mutationListener.onRemove(elementToRemove);
                evictionListener.accept(elementToRemove);
            }
        }
    }

    /**
     * Evict the eldest elements until the running byte total fits {@code maxBytes}, but never the last
     * element (so an incoming over-budget element is admitted rather than rejected). No-op when the
     * budget is disabled. Counts byte-driven evictions separately in {@link #byteEvictedCount}.
     */
    private void evictExcessBytes() {
        if (maxBytes <= 0 || weigher == null) {
            return;
        }
        while (totalBytes.get() > maxBytes && queueSize.get() > 1) {
            K keyToRemove = insertionOrderQueue.poll();
            if (keyToRemove == null) {
                queueSize.set(insertionOrderQueue.size());
                break;
            }
            queueSize.decrementAndGet();
            V elementToRemove = byKey.remove(keyToRemove);
            if (elementToRemove != null) {
                subtractWeight(elementToRemove);
                byteEvictedCount.incrementAndGet();
                sortOrderSkipList.remove(skipListKeyFunction.apply(elementToRemove));
                mutationListener.onRemove(elementToRemove);
                evictionListener.accept(elementToRemove);
            }
        }
    }

    private void addWeight(V element) {
        if (weigher != null) {
            totalBytes.addAndGet(weigher.applyAsLong(element));
        }
    }

    private void subtractWeight(V element) {
        if (weigher != null) {
            totalBytes.addAndGet(-weigher.applyAsLong(element));
        }
    }

    // Does NOT adjust totalBytes: used only for in-place priority re-keying of the same value object,
    // whose weight is unchanged. A value whose weight changes must go through replaceValue.
    public void removePriorityKey(V element) {
        sortOrderSkipList.remove(skipListKeyFunction.apply(element));
        // An in-place update is removePriorityKey(matcher-with-OLD-fields) then, after the
        // element is mutated, addPriorityKey(matcher-with-NEW-fields). Firing onRemove here lets a
        // derived index drop the element from its OLD placement before it is re-added below.
        mutationListener.onRemove(element);
        sortedCache = null;
    }

    public void addPriorityKey(V element) {
        sortOrderSkipList.add(skipListKeyFunction.apply(element));
        mutationListener.onAdd(element);
        sortedCache = null;
    }

    public void add(V element) {
        if (maxSize > 0 && element != null) {
            K key = mapKeyFunction.apply(element);
            // Publish to byKey BEFORE the insertion-order queue so a concurrent
            // reader never sees a key in the queue (or its eviction accounting)
            // whose value is not yet resolvable via byKey.
            byKey.put(key, element);
            sortOrderSkipList.add(skipListKeyFunction.apply(element));
            insertionOrderQueue.offer(key);
            queueSize.incrementAndGet();
            addWeight(element);
            // Notify BEFORE evictExcess so the add is reflected first and any resulting overflow
            // eviction (which fires its own onRemove) is applied on top, matching real order.
            mutationListener.onAdd(element);
            evictExcess();
            evictExcessBytes();
            sortedCache = null;
        }
    }

    /**
     * Replaces the value associated with the given key in place, preserving
     * the element's position in {@code insertionOrderQueue} (and therefore
     * its eviction order). Because the insertion queue holds keys and the key
     * is invariant on update, the queue is left untouched — the element keeps
     * its exact eviction slot. Only the byKey map and the priority sort keys
     * (old removed, new added) are updated. O(log n).
     *
     * @param key      the key that identifies the existing element
     * @param newValue the replacement value
     * @return {@code true} if the key was found and the value replaced
     */
    public boolean replaceValue(K key, V newValue) {
        V existing = byKey.get(key);
        if (existing == null) {
            return false;
        }
        // Update byKey
        byKey.put(key, newValue);
        subtractWeight(existing);
        addWeight(newValue);
        // Update priority sort: remove old, add new
        sortOrderSkipList.remove(skipListKeyFunction.apply(existing));
        sortOrderSkipList.add(skipListKeyFunction.apply(newValue));
        mutationListener.onRemove(existing);
        mutationListener.onAdd(newValue);
        evictExcessBytes();
        sortedCache = null;
        return true;
    }

    public boolean remove(V element) {
        if (element != null) {
            K key = mapKeyFunction.apply(element);
            if (insertionOrderQueue.remove(key)) {
                queueSize.decrementAndGet();
                // subtract the weight of the value actually stored for this key (exact after a replace)
                V stored = byKey.get(key);
                subtractWeight(stored != null ? stored : element);
            }
            byKey.remove(key);
            boolean removed = sortOrderSkipList.remove(skipListKeyFunction.apply(element));
            mutationListener.onRemove(element);
            sortedCache = null;
            return removed;
        } else {
            return false;
        }
    }

    public int size() {
        // O(1): the explicit counter, kept in step with insertionOrderQueue under the
        // single-writer contract. insertionOrderQueue.size() would be an O(n) traversal.
        return queueSize.get();
    }

    /** Current summed weight of retained elements (per the weigher); 0 when no weigher was supplied. */
    public long getTotalBytes() {
        return totalBytes.get();
    }

    /** The byte budget in force; {@code <= 0} means the byte bound is disabled. */
    public long getMaxBytes() {
        return maxBytes;
    }

    /** Overflow evictions driven by the byte budget rather than the element-count bound. */
    public long getByteEvictedCount() {
        return byteEvictedCount.get();
    }

    public Stream<V> stream() {
        return sortOrderSkipList.stream().map(item -> byKey.get(item.getKey())).filter(Objects::nonNull);
    }

    public Optional<V> getByKey(K key) {
        if (key != null && !"".equals(key)) {
            return Optional.ofNullable(byKey.get(key));
        } else {
            return Optional.empty();
        }
    }

    public Map<K, V> keyMap() {
        return new HashMap<>(byKey);
    }

    public boolean isEmpty() {
        return insertionOrderQueue.isEmpty();
    }

    /**
     * Returns a cached, unmodifiable sorted snapshot of this queue's elements.
     * The snapshot is rebuilt lazily when any mutation nulls the cache.
     * <p>
     * <b>Eventually-consistent under concurrent mutation:</b> a call to
     * this method concurrent with a control-plane mutation (add/remove/
     * reconcileFromBackend) may return a snapshot that does not yet reflect
     * the in-flight mutation. This is the existing control-plane / data-plane
     * concurrency contract — no lock is held on the matching hot path.
     */
    public List<V> toSortedList() {
        List<V> cached = sortedCache;
        if (cached == null) {
            cached = Collections.unmodifiableList(stream().collect(Collectors.toList()));
            sortedCache = cached;
        }
        return cached;
    }
}

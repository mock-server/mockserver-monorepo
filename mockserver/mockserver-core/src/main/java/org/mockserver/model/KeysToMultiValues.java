package org.mockserver.model;

import com.google.common.collect.ForwardingMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;

import static org.mockserver.model.NottableString.*;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class KeysToMultiValues<T extends KeyToMultiValue, K extends KeysToMultiValues> extends ObjectWithJsonToString {

    private static final NottableString[] EMPTY = new NottableString[0];

    // Above this size the per-key grouping used by getEntries/getValues/getFirstValue/equals/hashCode
    // switches from an O(n^2) linear scan to an O(n) HashMap pass. Below it the linear scan is faster
    // and allocation-free; above it the quadratic cost on caller-controlled input (a large form body or
    // query string) is capped. Typical headers/parameters sit well below this.
    private static final int GROUPING_THRESHOLD = 16;

    private KeyMatchStyle keyMatchStyle = KeyMatchStyle.SUB_SET;

    // Flat insertion-ordered store: keys[i]/values[i] is the i-th entry in true global insertion order.
    // A doubly-linked multimap costs ~1.4kB of structure to hold ~4 headers; two parallel arrays hold the
    // same order and duplicate-key semantics for a fraction of that. Global insertion order is load-bearing
    // (it is written straight onto the wire), so removals compact rather than swap.
    private NottableString[] keys = EMPTY;
    private NottableString[] values = EMPTY;
    private int size;
    private final K k = (K) this;

    // Lazily memoized request-side conversion (see #getConvertedMatcher / #setConvertedMatcher).
    // Two slots keyed by controlPlaneMatcher: index 0 = data-plane (false), index 1 = control-plane (true).
    // Cleared on every mutation via #clearConvertedMatcher (invoked from #isModified) so a mutated
    // collection never serves a stale conversion. A request-side collection is matched on a single I/O
    // thread, so this lazy cache is not contended; it is declared volatile only to make any future
    // cross-thread read see a fully-published array rather than a torn one, at negligible cost.
    private transient volatile Object[] convertedMatcher;

    protected KeysToMultiValues() {
    }

    protected KeysToMultiValues(Multimap<NottableString, NottableString> multimap) {
        if (multimap != null) {
            Collection<Map.Entry<NottableString, NottableString>> entries = multimap.entries();
            ensureCapacity(entries.size());
            for (Map.Entry<NottableString, NottableString> entry : entries) {
                append(entry.getKey(), entry.getValue());
            }
        }
    }

    private void ensureCapacity(int required) {
        if (keys.length < required) {
            int newCapacity = keys.length == 0 ? 4 : keys.length + (keys.length >> 1);
            if (newCapacity < required) {
                newCapacity = required;
            }
            keys = Arrays.copyOf(keys, newCapacity);
            values = Arrays.copyOf(values, newCapacity);
        }
    }

    private void append(NottableString key, NottableString value) {
        ensureCapacity(size + 1);
        keys[size] = key;
        values[size] = value;
        size++;
    }

    private void appendAll(NottableString key, Collection<NottableString> newValues) {
        ensureCapacity(size + newValues.size());
        for (NottableString value : newValues) {
            keys[size] = key;
            values[size] = value;
            size++;
        }
    }

    private void clearStore() {
        Arrays.fill(keys, 0, size, null);
        Arrays.fill(values, 0, size, null);
        size = 0;
    }

    /**
     * Key identity as the replaced {@link LinkedListMultimap} saw it: its key index is a hash map, so two
     * keys are the same key only when their hashes match AND they are equal. NottableString deliberately
     * breaks the equals/hashCode contract for negation matching (a NOT key equals a differently-valued plain
     * key yet hashes differently), so an equals-only scan would wrongly merge such keys; the hash guard,
     * mirroring HashMap's node match, keeps them distinct exactly as the multimap did.
     */
    private static boolean sameKey(NottableString a, NottableString b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.hashCode() == b.hashCode() && a.equals(b);
    }

    private boolean isFirstOccurrence(NottableString key, int index) {
        for (int i = 0; i < index; i++) {
            if (sameKey(keys[i], key)) {
                return false;
            }
        }
        return true;
    }

    private List<NottableString> valuesForExactKey(NottableString key) {
        List<NottableString> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (sameKey(keys[i], key)) {
                result.add(values[i]);
            }
        }
        return result;
    }

    /**
     * Groups the store by key in first-occurrence order in a single O(n) pass. A HashMap keys the same way
     * the replaced multimap did (hash then equals), so NOT-key identity is preserved and the result matches
     * the linear scan exactly. Used only above {@link #GROUPING_THRESHOLD}.
     */
    private LinkedHashMap<NottableString, List<NottableString>> groupByKey() {
        LinkedHashMap<NottableString, List<NottableString>> grouped = new LinkedHashMap<>(size * 2);
        for (int i = 0; i < size; i++) {
            grouped.computeIfAbsent(keys[i], ignored -> new ArrayList<>()).add(values[i]);
        }
        return grouped;
    }

    /**
     * Returns the memoized request-side conversion for the given control-plane flag, or {@code null} if
     * not yet built. The conversion is intentionally keyed by {@code controlPlaneMatcher} because the
     * converted form embeds a control-plane-sensitive matcher; a data-plane conversion must never be
     * served to a control-plane caller or vice versa.
     */
    public Object getConvertedMatcher(boolean controlPlaneMatcher) {
        Object[] cache = convertedMatcher;
        return cache == null ? null : cache[controlPlaneMatcher ? 1 : 0];
    }

    /**
     * Stores the memoized request-side conversion for the given control-plane flag. The cache is cleared
     * automatically on any mutation (see #isModified), so callers may safely reuse the value for the
     * lifetime of an unmutated collection (e.g. across a single request's expectation scan).
     */
    public void setConvertedMatcher(boolean controlPlaneMatcher, Object converted) {
        Object[] cache = convertedMatcher;
        if (cache == null) {
            cache = new Object[2];
            convertedMatcher = cache;
        }
        cache[controlPlaneMatcher ? 1 : 0] = converted;
    }

    protected void clearConvertedMatcher() {
        convertedMatcher = null;
    }

    public abstract T build(final NottableString name, final Collection<NottableString> values);

    /**
     * Invoked from every mutating method before the underlying store is changed. Clears the memoized
     * request-side conversion so a mutated collection never serves a stale conversion. Subclasses that
     * override this MUST call {@code super.isModified()}.
     */
    protected void isModified() {
        clearConvertedMatcher();
    }

    public KeyMatchStyle getKeyMatchStyle() {
        return keyMatchStyle;
    }

    @SuppressWarnings("UnusedReturnValue")
    public KeysToMultiValues<T, K> withKeyMatchStyle(KeyMatchStyle keyMatchStyle) {
        isModified();
        this.keyMatchStyle = keyMatchStyle;
        return this;
    }

    public K withEntries(final Map<String, List<String>> entries) {
        isModified();
        clearStore();
        reserve(entries.size());
        for (Map.Entry<String, List<String>> entry : entries.entrySet()) {
            final NottableString name = NottableString.string(entry.getKey(), false);
            for (String value : entry.getValue()) {
                append(name, NottableString.string(value, false));
            }
        }
        return k;
    }

    public K withEntries(final List<T> entries) {
        isModified();
        clearStore();
        if (entries != null) {
            for (T entry : entries) {
                withEntry(entry);
            }
        }
        return k;
    }

    @SafeVarargs
    public final K withEntries(final T... entries) {
        if (ArrayUtils.isNotEmpty(entries)) {
            withEntries(Arrays.asList(entries));
        }
        return k;
    }

    public K withEntry(final T entry) {
        if (entry != null) {
            isModified();
            if (entry.getValues().isEmpty()) {
                append(entry.getName(), string(""));
            } else {
                appendAll(entry.getName(), entry.getValues());
            }
        }
        return k;
    }

    public K withEntry(final String name, final String... values) {
        isModified();
        if (values == null || values.length == 0) {
            append(string(name), string(""));
        } else {
            appendAll(string(name), deserializeNottableStrings(values));
        }
        return k;
    }

    public K withEntry(final String name, final List<String> values) {
        isModified();
        if (values == null || values.size() == 0) {
            append(string(name), string(""));
        } else {
            appendAll(string(name), deserializeNottableStrings(values));
        }
        return k;
    }

    public K withEntry(final NottableString name, final List<NottableString> values) {
        if (values != null) {
            isModified();
            appendAll(name, values);
        }
        return k;
    }

    public K withEntry(final NottableString name, final NottableString... values) {
        if (ArrayUtils.isNotEmpty(values)) {
            withEntry(name, Arrays.asList(values));
        }
        return k;
    }

    /**
     * Append one already-wrapped literal name/value pair in true wire order, with no matcher parsing and
     * no intermediate collection. Exists for single-pass message-header ingest, which has already wrapped
     * the name via {@link NottableString#headerName} and the value via
     * {@link NottableString#string(String, Boolean)} with {@code not = false}; the public
     * {@link #withEntry(NottableString, List)} would instead force construction of the very per-name
     * {@link List} that single-pass ingest removes. Public only because the netty mapper lives in another
     * package — callers MUST pass literal (non-matcher) strings.
     */
    public void appendLiteral(final NottableString name, final NottableString value) {
        isModified();
        append(name, value);
    }

    /**
     * Pre-grow the flat store to hold at least {@code additionalEntries} further entries, so a caller that
     * knows the count up front (netty exposes header count as an O(1) size()) sizes the store once instead
     * of incurring the incremental array-growth garbage of repeated {@link #appendLiteral}.
     */
    public void reserve(final int additionalEntries) {
        if (additionalEntries > 0) {
            ensureCapacity(size + additionalEntries);
        }
    }

    public boolean remove(final String name) {
        boolean exists = false;
        if (name != null) {
            isModified();
            exists = removeMatchingKeys(name);
        }
        return exists;
    }

    public boolean remove(final NottableString name) {
        boolean exists = false;
        if (name != null) {
            isModified();
            exists = removeMatchingKeys(name);
        }
        return exists;
    }

    /**
     * Removes every entry whose key case-insensitively matches {@code name}, compacting survivors so their
     * global insertion order is preserved. A swap-remove would be O(1) but scramble the order the response
     * writer reads onto the wire, so removal must shift.
     */
    private boolean removeMatchingKeys(Object name) {
        int write = 0;
        boolean removed = false;
        for (int read = 0; read < size; read++) {
            NottableString key = keys[read];
            if (key != null && key.equalsIgnoreCase(name)) {
                removed = true;
            } else {
                keys[write] = keys[read];
                values[write] = values[read];
                write++;
            }
        }
        if (removed) {
            Arrays.fill(keys, write, size, null);
            Arrays.fill(values, write, size, null);
            size = write;
        }
        return removed;
    }

    @SuppressWarnings("UnusedReturnValue")
    public K replaceEntry(final T entry) {
        if (entry != null) {
            isModified();
            remove(entry.getName());
            appendAll(entry.getName(), entry.getValues());
        }
        return k;
    }

    @SuppressWarnings("UnusedReturnValue")
    public K replaceEntryIfExists(final T entry) {
        if (entry != null) {
            isModified();
            if (remove(entry.getName())) {
                appendAll(entry.getName(), entry.getValues());
            }
        }
        return k;
    }

    @SuppressWarnings("UnusedReturnValue")
    public K replaceEntry(final String name, final String... values) {
        if (ArrayUtils.isNotEmpty(values)) {
            isModified();
            remove(name);
            appendAll(string(name), deserializeNottableStrings(values));
        }
        return k;
    }

    public List<T> getEntries() {
        if (isEmpty()) {
            return Collections.emptyList();
        }
        if (size > GROUPING_THRESHOLD) {
            LinkedHashMap<NottableString, List<NottableString>> grouped = groupByKey();
            ArrayList<T> headers = new ArrayList<>(grouped.size());
            for (Map.Entry<NottableString, List<NottableString>> entry : grouped.entrySet()) {
                headers.add(build(entry.getKey(), entry.getValue()));
            }
            return headers;
        }
        ArrayList<T> headers = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            NottableString key = keys[i];
            if (isFirstOccurrence(key, i)) {
                headers.add(build(key, valuesForExactKey(key)));
            }
        }
        return headers;
    }

    public Set<NottableString> keySet() {
        Set<NottableString> distinct = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            distinct.add(keys[i]);
        }
        return distinct;
    }

    public Collection<NottableString> getValues(NottableString key) {
        return valuesForExactKey(key);
    }

    public Multimap<NottableString, NottableString> getMultimap() {
        return new ReadOnlyInsertionOrderedMultimap(keys, values, size);
    }

    public List<String> getValues(final String name) {
        if (isEmpty() || name == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        if (size > GROUPING_THRESHOLD) {
            for (Map.Entry<NottableString, List<NottableString>> entry : groupByKey().entrySet()) {
                NottableString key = entry.getKey();
                if (key != null && key.equalsIgnoreCase(name)) {
                    values.addAll(serialiseNottableStrings(entry.getValue()));
                }
            }
            return values;
        }
        for (int i = 0; i < size; i++) {
            NottableString key = keys[i];
            if (isFirstOccurrence(key, i) && key != null && key.equalsIgnoreCase(name)) {
                values.addAll(serialiseNottableStrings(valuesForExactKey(key)));
            }
        }
        return values;
    }

    String getFirstValue(final String name) {
        if (isEmpty()) {
            return "";
        }
        if (size > GROUPING_THRESHOLD) {
            for (Map.Entry<NottableString, List<NottableString>> entry : groupByKey().entrySet()) {
                NottableString key = entry.getKey();
                if (key != null && key.equalsIgnoreCase(name)) {
                    List<NottableString> nottableStrings = entry.getValue();
                    if (!nottableStrings.isEmpty()) {
                        NottableString next = nottableStrings.get(0);
                        if (next != null) {
                            return next.getValue();
                        }
                    }
                }
            }
            return "";
        }
        for (int i = 0; i < size; i++) {
            NottableString key = keys[i];
            // Cheap name test first, then the O(i) first-occurrence scan, so a non-matching key
            // short-circuits before the scan (a miss is O(n), not O(n^2)). At a first occurrence
            // values[i] is exactly valuesForExactKey(key).get(0), so return it directly.
            if (key != null && key.equalsIgnoreCase(name) && isFirstOccurrence(key, i)) {
                NottableString value = values[i];
                if (value != null) {
                    return value.getValue();
                }
            }
        }
        return "";
    }

    public boolean containsEntry(final String name) {
        if (!isEmpty()) {
            for (int i = 0; i < size; i++) {
                NottableString key = keys[i];
                if (key != null && key.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean containsEntry(final String name, final String value) {
        return containsEntry(string(name), string(value));
    }

    public boolean containsEntry(final NottableString name, final NottableString value) {
        if (!isEmpty() && name != null && value != null) {
            for (int i = 0; i < size; i++) {
                NottableString entryKey = keys[i];
                if (entryKey != null && entryKey.equalsIgnoreCase(name)) {
                    NottableString entryValue = values[i];
                    if (value.equalsIgnoreCase(entryValue)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public abstract K clone();

    /**
     * For a deep copy: gives {@code target}, an empty collection, copies of these entries and this key match style.
     * The entries are immutable {@link NottableString}s, so copying the arrays copies the collection.
     */
    K copyEntriesInto(K target) {
        KeysToMultiValues<?, ?> copy = target;
        copy.keys = keys.length == 0 ? EMPTY : keys.clone();
        copy.values = values.length == 0 ? EMPTY : values.clone();
        copy.size = size;
        copy.keyMatchStyle = keyMatchStyle;
        return target;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KeysToMultiValues)) {
            return false;
        }
        KeysToMultiValues<?, ?> that = (KeysToMultiValues<?, ?>) o;
        // Guava's ListMultimap equality is asMap-based: key order is irrelevant, per-key value order is not.
        if (size != that.size) {
            return false;
        }
        if (size > GROUPING_THRESHOLD) {
            return groupByKey().equals(that.groupByKey());
        }
        for (int i = 0; i < size; i++) {
            NottableString key = keys[i];
            if (isFirstOccurrence(key, i)
                && !valuesForExactKey(key).equals(that.valuesForExactKey(key))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        // Reproduces Objects.hash(LinkedListMultimap): 31 + asMap().hashCode(), where the per-key value
        // collections are Lists, so key order does not affect the result but per-key value order does.
        int mapHashCode = 0;
        if (size > GROUPING_THRESHOLD) {
            for (Map.Entry<NottableString, List<NottableString>> entry : groupByKey().entrySet()) {
                NottableString key = entry.getKey();
                mapHashCode += (key == null ? 0 : key.hashCode()) ^ entry.getValue().hashCode();
            }
            return 31 + mapHashCode;
        }
        for (int i = 0; i < size; i++) {
            NottableString key = keys[i];
            if (isFirstOccurrence(key, i)) {
                mapHashCode += (key == null ? 0 : key.hashCode()) ^ valuesForExactKey(key).hashCode();
            }
        }
        return 31 + mapHashCode;
    }

    /**
     * Read-only {@link Multimap} projection built on demand from the flat store. {@link #entries()},
     * {@link #size()} and {@link #isEmpty()} read the arrays directly so the hot response-write path (which
     * iterates {@code entries()} onto the wire) allocates no multimap machinery; every other view lazily
     * materialises an unmodifiable {@link LinkedListMultimap} so Guava's semantics are reproduced exactly.
     *
     * <p>This is a live read-only view over the source arrays, NOT a defensive copy (copying would allocate
     * on the hot response-write path). It reads the store as it was sized when {@code getMultimap()} was
     * called, but an in-place mutation of the source afterwards (remove/clear compacts and null-fills the
     * same arrays) would be observed through it. Callers must not retain the view across a mutation of the
     * source collection; every current caller consumes it immediately.
     */
    private static final class ReadOnlyInsertionOrderedMultimap extends ForwardingMultimap<NottableString, NottableString> {

        private final NottableString[] keys;
        private final NottableString[] values;
        private final int size;
        private Multimap<NottableString, NottableString> delegate;

        ReadOnlyInsertionOrderedMultimap(NottableString[] keys, NottableString[] values, int size) {
            this.keys = keys;
            this.values = values;
            this.size = size;
        }

        @Override
        protected Multimap<NottableString, NottableString> delegate() {
            Multimap<NottableString, NottableString> current = delegate;
            if (current == null) {
                LinkedListMultimap<NottableString, NottableString> built = LinkedListMultimap.create();
                for (int i = 0; i < size; i++) {
                    built.put(keys[i], values[i]);
                }
                current = Multimaps.unmodifiableMultimap(built);
                delegate = current;
            }
            return current;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean isEmpty() {
            return size == 0;
        }

        @Override
        public Collection<Map.Entry<NottableString, NottableString>> entries() {
            return new AbstractCollection<Map.Entry<NottableString, NottableString>>() {
                @Override
                public int size() {
                    return size;
                }

                @Override
                public Iterator<Map.Entry<NottableString, NottableString>> iterator() {
                    return new Iterator<Map.Entry<NottableString, NottableString>>() {
                        private int index;

                        @Override
                        public boolean hasNext() {
                            return index < size;
                        }

                        @Override
                        public Map.Entry<NottableString, NottableString> next() {
                            if (index >= size) {
                                throw new NoSuchElementException();
                            }
                            Map.Entry<NottableString, NottableString> entry =
                                new AbstractMap.SimpleImmutableEntry<>(keys[index], values[index]);
                            index++;
                            return entry;
                        }
                    };
                }
            };
        }
    }
}

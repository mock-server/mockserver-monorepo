package org.mockserver.matchers;

import com.google.common.collect.Maps;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.RequestDefinition;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.slf4j.event.Level.TRACE;

public class MatchDifference {

    public enum Field {
        METHOD("method"),
        PATH("path"),
        PATH_PARAMETERS("pathParameters"),
        QUERY_PARAMETERS("queryParameters"),
        COOKIES("cookies"),
        HEADERS("headers"),
        BODY("body"),
        SECURE("secure"),
        PROTOCOL("protocol"),
        CLIENT_CERTIFICATE("clientCertificate"),
        JWT("jwt"),
        KEEP_ALIVE("keep-alive"),
        OPERATION("operation"),
        OPENAPI("openapi"),
        DNS_NAME("dnsName"),
        DNS_TYPE("dnsType"),
        DNS_CLASS("dnsClass"),
        BINARY_BODY("binaryBody");

        private final String name;

        Field(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    private static final Object[] EMPTY_ARGUMENTS = new Object[0];

    private final boolean detailedMatchFailures;
    private final RequestDefinition httpRequest;
    // Lazily allocated on the first recorded difference. A MatchDifference is request- /
    // evaluation-scoped and single-threaded (see the suppressMatchResultLogging note below),
    // so a plain HashMap is sufficient — no ConcurrentHashMap is required. When no difference
    // is recorded (the common case, and always when detailedMatchFailures is off) this stays
    // null and no map is allocated. All read paths below tolerate a null map.
    //
    // Each recorded difference is held as a LazyMessage: the human-readable diff string is the
    // #1/#2 allocation source under sustained load (StringFormatter.indentAndToString /
    // formatLogMessage), yet it is only ever consumed if someone later reads the differences.
    // So the expensive indentation/assembly is deferred to first read (and cached), while the
    // arguments are snapshotted to their string form NOW, at comparison time, so a later read
    // reports what the fields said when they were compared — not any post-comparison mutation
    // of the request/response models. The map KEYS remain populated eagerly so the field-count
    // reads (getAllDifferences().size(), containsKey) stay cheap and force no formatting.
    private Map<Field, List<LazyMessage>> differences;
    private Field fieldName;
    // When true, a matcher computing differences against this context must NOT emit
    // EXPECTATION_MATCHED / EXPECTATION_NOT_MATCHED events to the event log. Read-only
    // diagnostics (explainUnmatched, debugMismatch) re-run real matchers purely to
    // collect field differences and must not pollute the very log they inspect — those
    // entries also lack a request correlationId, so they cannot be grouped in the dashboard
    // and would saturate its bounded log window. This flag is request-scoped (a fresh
    // MatchDifference per evaluation), so it is thread-safe unlike toggling the matcher.
    private boolean suppressMatchResultLogging;
    // When true, a matcher must evaluate EVERY field rather than returning on the first
    // non-matching one, so that this context ends up holding the complete set of differing
    // fields. Diagnostics that rank expectations by how close they were (debugMismatch)
    // cannot do so otherwise: fail-fast stops at the first difference, so every mismatched
    // expectation records exactly one differing field and they all tie. Like
    // suppressMatchResultLogging this is request-scoped (a fresh MatchDifference per
    // evaluation), so it is thread-safe — unlike toggling the matcher's failFast
    // configuration, which is global and would change production matching for concurrent
    // requests. The final match result is unaffected: it is
    // applyNotOperators(failures == 0, ...) computed once all fields are evaluated, which
    // is the same verdict the short-circuit reaches by a shorter route.
    private boolean collectAllDifferences;

    public MatchDifference(boolean detailedMatchFailures, RequestDefinition httpRequest) {
        this.detailedMatchFailures = detailedMatchFailures;
        this.httpRequest = httpRequest;
    }

    /**
     * Mark this difference context as diagnostic-only: matchers must compute differences without
     * logging EXPECTATION_MATCHED / EXPECTATION_NOT_MATCHED events. Used by read-only endpoints
     * (explainUnmatched, debugMismatch) so they do not write side-effect entries into the event log.
     */
    public MatchDifference suppressMatchResultLogging() {
        this.suppressMatchResultLogging = true;
        return this;
    }

    public boolean isSuppressMatchResultLogging() {
        return suppressMatchResultLogging;
    }

    /**
     * Ask matchers to evaluate every field instead of stopping at the first non-matching one, so
     * this context collects the complete set of differing fields.
     * <p>
     * Only for read-only diagnostics that need to know <em>how much</em> of a request matched
     * (notably {@code debugMismatch}, which ranks expectations by closeness). It is scoped to this
     * one evaluation and never touches the shared matcher configuration, so it cannot slow or alter
     * matching for any other request.
     */
    public MatchDifference collectAllDifferences() {
        this.collectAllDifferences = true;
        return this;
    }

    public boolean isCollectAllDifferences() {
        return collectAllDifferences;
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(MockServerLogger mockServerLogger, Throwable throwable, String messageFormat, Object... arguments) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setHttpRequest(httpRequest)
                    .setCorrelationId(httpRequest.getLogCorrelationId())
                    .setMessageFormat(messageFormat)
                    .setArguments(arguments)
                    .setThrowable(throwable)
            );
        }
        return addDifference(messageFormat, arguments);
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(MockServerLogger mockServerLogger, String messageFormat, Object... arguments) {
        return addDifference(mockServerLogger, (Throwable) null, messageFormat, arguments);
    }

    // Fixed-arity overloads for the common matcher call sites. The varargs forms above build their
    // Object[] at the call site — before any guard — so the array is allocated per candidate per
    // failed field and then discarded whenever nothing consumes it. These overloads consult
    // recordsNothing first and only build the array (delegating to the varargs form, whose behaviour
    // is unchanged) when a difference will actually be logged or retained. The rendered text is
    // identical: the delegate receives the same arguments in the same order.
    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(MockServerLogger mockServerLogger, String messageFormat) {
        if (recordsNothing(mockServerLogger)) {
            return this;
        }
        return addDifference(mockServerLogger, messageFormat, EMPTY_ARGUMENTS);
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(MockServerLogger mockServerLogger, String messageFormat, Object argumentOne, Object argumentTwo) {
        if (recordsNothing(mockServerLogger)) {
            return this;
        }
        return addDifference(mockServerLogger, messageFormat, new Object[]{argumentOne, argumentTwo});
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(MockServerLogger mockServerLogger, String messageFormat, Object argumentOne, Object argumentTwo, Object argumentThree) {
        if (recordsNothing(mockServerLogger)) {
            return this;
        }
        return addDifference(mockServerLogger, messageFormat, new Object[]{argumentOne, argumentTwo, argumentThree});
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(MockServerLogger mockServerLogger, String messageFormat, Object argumentOne, Object argumentTwo, Object argumentThree, Object argumentFour) {
        if (recordsNothing(mockServerLogger)) {
            return this;
        }
        return addDifference(mockServerLogger, messageFormat, new Object[]{argumentOne, argumentTwo, argumentThree, argumentFour});
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(MockServerLogger mockServerLogger, Throwable throwable, String messageFormat) {
        if (recordsNothing(mockServerLogger)) {
            return this;
        }
        return addDifference(mockServerLogger, throwable, messageFormat, EMPTY_ARGUMENTS);
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(MockServerLogger mockServerLogger, Throwable throwable, String messageFormat, Object argumentOne, Object argumentTwo, Object argumentThree) {
        if (recordsNothing(mockServerLogger)) {
            return this;
        }
        return addDifference(mockServerLogger, throwable, messageFormat, new Object[]{argumentOne, argumentTwo, argumentThree});
    }

    // A fixed-arity call records nothing when neither TRACE logging (which would emit the diff as a
    // log event) nor detailedMatchFailures (which would retain it for explainUnmatched) is active,
    // so no Object[] need be built. This is exactly the pair of consumption conditions the varargs
    // forms already gate their two uses on, so early-returning here changes no observable output.
    private boolean recordsNothing(MockServerLogger mockServerLogger) {
        return !detailedMatchFailures
            && (mockServerLogger == null || !mockServerLogger.isEnabledForInstance(TRACE));
    }

    public MatchDifference addDifference(Field fieldName, String messageFormat, Object... arguments) {
        if (detailedMatchFailures) {
            if (isNotBlank(messageFormat) && arguments != null && fieldName != null) {
                if (this.differences == null) {
                    this.differences = new HashMap<>();
                }
                this.differences
                    .computeIfAbsent(fieldName, key -> new ArrayList<>())
                    .add(new LazyMessage(messageFormat, arguments));
            }
        }
        return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(String messageFormat, Object... arguments) {
        return addDifference(fieldName, messageFormat, arguments);
    }

    public RequestDefinition getHttpRequest() {
        return httpRequest;
    }

    public String getLogCorrelationId() {
        return httpRequest.getLogCorrelationId();
    }

    @SuppressWarnings("UnusedReturnValue")
    protected MatchDifference currentField(Field fieldName) {
        this.fieldName = fieldName;
        return this;
    }

    public List<String> getDifferences(Field fieldName) {
        // Preserve the prior contract: a missing field returns null (the lazy map being
        // unallocated is equivalent to the field being absent). The returned list formats
        // each entry on access (and caches it), so a caller that only checks isEmpty()/size()
        // forces no formatting; iterating it (e.g. joining for the "because" log) does.
        if (this.differences == null) {
            return null;
        }
        List<LazyMessage> messages = this.differences.get(fieldName);
        return messages == null ? null : new LazyStringList(messages);
    }

    public Map<Field, List<String>> getAllDifferences() {
        // Must return empty (never null) when nothing was recorded. A live view over the
        // lazy map: size()/keySet()/containsKey() delegate to the backing map (cheap — the
        // per-candidate closest-match diagnostic only reads size()), while iterating an
        // entry's values formats them lazily.
        return this.differences == null
            ? Collections.emptyMap()
            : Maps.transformValues(this.differences, LazyStringList::new);
    }

    public void addDifferences(Map<Field, List<String>> differences) {
        if (differences == null || differences.isEmpty()) {
            return;
        }
        if (this.differences == null) {
            this.differences = new HashMap<>();
        }
        for (Field field : differences.keySet()) {
            List<LazyMessage> target = this.differences.computeIfAbsent(field, key -> new ArrayList<>());
            for (String difference : differences.get(field)) {
                // The incoming strings are already materialised (they come from another
                // MatchDifference's getAllDifferences() view), so wrap them as-is.
                target.add(new LazyMessage(difference));
            }
        }
    }

    /**
     * A recorded difference whose human-readable string is formatted lazily on first read and
     * then cached. The arguments are captured as strings at construction time (comparison time)
     * so the deferred format is byte-identical to the previous eager
     * {@code formatLogMessage(1, messageFormat, arguments)} and cannot be corrupted by a later
     * mutation of the argument objects.
     */
    private static final class LazyMessage {
        // When messageFormat is null the difference was supplied already-formatted (see the
        // String constructor) and 'formatted' holds it directly.
        //
        // Not thread-safe by design: a MatchDifference and its LazyMessages are single-threaded,
        // request-scoped objects (see the class-level note), so 'formatted' is cached without a
        // volatile/lock. Even a benign race would only recompute the same deterministic string.
        private final String messageFormat;
        private final String[] arguments;
        private String formatted;

        private LazyMessage(String messageFormat, Object... arguments) {
            this.messageFormat = messageFormat;
            this.arguments = new String[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                // Snapshot NOW: String.valueOf is exactly what indentAndToString applies to each
                // argument FIRST (before any indentation), so a pre-stringified String passes
                // through it unchanged and get() reproduces byte-identical output to the old eager
                // formatLogMessage(1, messageFormat, arguments). This identity holds only while
                // indentAndToString stringifies via String.valueOf before any other transform — if
                // that ordering changes, revisit this snapshot.
                this.arguments[i] = String.valueOf(arguments[i]);
            }
        }

        private LazyMessage(String alreadyFormatted) {
            this.messageFormat = null;
            this.arguments = null;
            this.formatted = alreadyFormatted;
        }

        private String get() {
            if (formatted == null) {
                formatted = formatLogMessage(1, messageFormat, (Object[]) arguments);
            }
            return formatted;
        }
    }

    /**
     * A {@code List<String>} view over the recorded {@link LazyMessage}s for one field. size()
     * and isEmpty() are cheap (no formatting); element access formats (and caches) that entry.
     */
    private static final class LazyStringList extends AbstractList<String> {
        private final List<LazyMessage> backing;

        private LazyStringList(List<LazyMessage> backing) {
            this.backing = backing;
        }

        @Override
        public String get(int index) {
            return backing.get(index).get();
        }

        @Override
        public int size() {
            return backing.size();
        }
    }
}

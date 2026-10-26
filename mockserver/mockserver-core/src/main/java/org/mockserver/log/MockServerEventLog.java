package org.mockserver.log;

import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.mockserver.collections.CircularConcurrentLinkedDeque;
import org.mockserver.configuration.Configuration;
import org.mockserver.filters.TransportHeaderFilter;
import org.mockserver.log.model.LogEntry;
import org.mockserver.log.model.LogEntryMessages;
import org.mockserver.log.model.RequestAndExpectationId;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.HttpResponseMatcher;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.MatchDifferenceFormatter;
import org.mockserver.matchers.MatcherBuilder;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerEventLogNotifier;
import org.mockserver.mock.listeners.MockServerLogListener;
import org.mockserver.model.BinaryRequestDefinition;
import org.mockserver.model.DnsRequestDefinition;
import org.mockserver.model.ExpectationId;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.HttpResponseSerializer;
import org.mockserver.serialization.RequestDefinitionSerializer;
import org.mockserver.time.EpochService;
import org.mockserver.uuid.UUIDService;
import org.mockserver.verify.Disposition;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.mockserver.verify.VerificationTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.log.model.LogEntryMessages.VERIFICATION_REQUESTS_MESSAGE_FORMAT;
import static org.mockserver.log.model.LogEntryMessages.VERIFICATION_REQUEST_SEQUENCES_MESSAGE_FORMAT;
import static org.mockserver.logging.MockServerLogger.writeToSystemOut;
import static org.mockserver.mock.HttpState.getPort;
import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class MockServerEventLog extends MockServerEventLogNotifier {

    /**
     * Why a log event was dropped before being recorded; {@link #metricLabel()} is the {@code reason}
     * label of {@code mock_server_dropped_log_events}.
     */
    public enum DropReason {
        /** The ring buffer had no free slot: events arrived faster than the logging thread drains them. */
        RING_FULL("ring_full"),
        /** Admitting the event's bodies would have exceeded the in-flight byte cap. */
        IN_FLIGHT_BYTES("in_flight_bytes");

        private final String metricLabel;

        DropReason(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(MockServerEventLog.class);
    /**
     * Hard upper bound (60s) on a server-side eventual-verification {@code timeout}. A client-supplied
     * {@link Verification#getTimeout()} / {@link VerificationSequence#getTimeout()} larger than this is
     * silently clamped to this value so a single verify request can never pin server resources (a
     * scheduled deadline task plus a transient log listener) for an unbounded period. 60s comfortably
     * covers realistic async-application settle times (the Java client's own client-side poll defaults
     * are in the single-digit seconds) while bounding worst-case resource hold. The wait itself never
     * holds a Netty I/O thread — completion is delivered asynchronously through the existing result
     * consumer + scheduler, never via a blocking sleep on the request thread.
     */
    static final long MAX_VERIFY_TIMEOUT_MILLIS = 60_000L;
    private static final Predicate<LogEntry> allPredicate = input
        -> true;
    private static final Predicate<LogEntry> notDeletedPredicate = input
        -> !input.isDeleted();
    private static final Predicate<LogEntry> requestLogPredicate = input
        -> !input.isDeleted() && input.getType() == RECEIVED_REQUEST;
    // matches only responses the configured mocks actually produced (a matched expectation
    // response or a forwarded/proxied response), NOT MockServer's own auto-generated no-match
    // responses (NO_MATCH_RESPONSE, e.g. the default 404 for an unmatched request). Used both for
    // "matching expectations only" request retrieval (through answeredRequestLogPredicate) AND for response
    // VERIFICATION (responseVerificationLogPredicate is an alias for that second use) — deliberately narrower
    // than requestResponseLogPredicate, which is shared with /retrieve and must keep NO_MATCH_RESPONSE.
    private static final Predicate<LogEntry> expectationLogPredicate = input
        -> !input.isDeleted() && (
        input.getType() == EXPECTATION_RESPONSE
            || input.getType() == FORWARDED_REQUEST
    );
    // response VERIFICATION must exclude MockServer's own NO_MATCH_RESPONSE auto-404s and count
    // only mock-produced responses — the same filter as expectationLogPredicate; aliased here so
    // the verification call sites read clearly and a future change to one is forced to consider both.
    private static final Predicate<LogEntry> responseVerificationLogPredicate = expectationLogPredicate;
    private static final Predicate<LogEntry> requestResponseLogPredicate = input
        -> !input.isDeleted() && (
        input.getType() == EXPECTATION_RESPONSE
            || input.getType() == NO_MATCH_RESPONSE
            || input.getType() == FORWARDED_REQUEST
    );
    private static final Predicate<LogEntry> notDnsOrBinaryRequest = input
        -> !(input.getHttpRequest() instanceof DnsRequestDefinition || input.getHttpRequest() instanceof BinaryRequestDefinition);
    // A DNS query or binary message has no response entry: its received-request entry carries the id of the
    // expectation that answered it, so reads of the requests expectations answered count it as well.
    private static final Predicate<LogEntry> answeredDnsOrBinaryRequest = input
        -> !input.isDeleted() && input.getType() == RECEIVED_REQUEST && isNotBlank(input.getExpectationId()) && notDnsOrBinaryRequest.negate().test(input);
    private static final Predicate<LogEntry> answeredRequestLogPredicate = expectationLogPredicate.or(answeredDnsOrBinaryRequest);
    private static final Predicate<LogEntry> recordedExpectationLogPredicate = input
        -> !input.isDeleted() && input.getType() == FORWARDED_REQUEST;
    // Disposition predicates for verify-by-disposition (Verification.withDisposition). A MOCKED
    // request is one that matched an expectation and produced a mocked response (EXPECTATION_RESPONSE);
    // a FORWARDED request is one that was forwarded/proxied to an upstream server (FORWARDED_REQUEST).
    // NO_MATCH_RESPONSE (MockServer's own auto-404 for unmatched requests) is excluded from both.
    private static final Predicate<LogEntry> mockedRequestLogPredicate = input
        -> !input.isDeleted() && input.getType() == EXPECTATION_RESPONSE;
    private static final Predicate<LogEntry> forwardedRequestLogPredicate = input
        -> !input.isDeleted() && input.getType() == FORWARDED_REQUEST;
    // Every recorded-traffic-to-expectation surface (retrieve RECORDED_EXPECTATIONS in each format, promote,
    // cassette record, persisted recorded expectations) reads through here, so one rule keeps hop-by-hop and
    // transport headers out of all of them. The stored entry is never modified.
    private static final Function<LogEntry, Expectation> logEntryToRecordedExpectation =
        logEntry -> TransportHeaderFilter.withoutTransportHeaders(logEntry.getExpectation());
    // Redaction-aware getter: when mockserver.redactSecretsInLog is enabled it masks sensitive
    // headers / body fields on clones so retrieveRecordedRequests (and the JSON / HAR / cURL /
    // OpenAPI / Postman export formats derived from it) does not leak proxied credentials. When the
    // flag is off (the default) it returns the raw requests byte-for-byte unchanged. Request matching
    // and verification read the un-redacted fields directly elsewhere; this mapper is used only on the
    // request-retrieval / export surface (where the result is displayed/exported, or counted — and
    // redaction never adds/drops entries, so the verification count is unaffected).
    // Raw request/response pair — used by the response-aware verification DECISION path, which must
    // match against the original (un-redacted) content so enabling redaction never changes a
    // verification pass/fail result.
    private static final Function<LogEntry, LogEventRequestAndResponse> logEntryToHttpRequestAndHttpResponse =
        logEntry -> new LogEventRequestAndResponse()
            .withHttpRequest((HttpRequest) logEntry.getHttpRequest())
            .withHttpResponse(logEntry.getHttpResponse())
            .withTimestamp(logEntry.getTimestamp());
    // Redacted request/response pair — used only by the retrieveRecordedRequestsAndResponses
    // retrieve/export surface, so secrets are masked in the exported/displayed copy while the
    // verification path above keeps matching raw content.
    private static final String[] EXCLUDED_FIELDS = {"id", "disruptor"};
    private final Configuration configuration;
    // Instance-scoped (not static) so redaction consults this server's Configuration instance —
    // a static method reference could only ever see the global ConfigurationProperties store, so
    // redactSecretsInLog set programmatically or via PUT /mockserver/configuration would not apply.
    // Declared after `configuration` so the blank final is definitely assigned before these
    // initializers run.
    private final Function<LogEntry, RequestDefinition[]> logEntryToRequest;
    // Redacted request/response pair — used only by the retrieveRecordedRequestsAndResponses
    // retrieve/export surface, so secrets are masked in the exported/displayed copy while the
    // verification path above keeps matching raw content.
    private final Function<LogEntry, LogEventRequestAndResponse> logEntryToRedactedHttpRequestAndHttpResponse;
    private MockServerLogger mockServerLogger;
    private CircularConcurrentLinkedDeque<LogEntry> eventLog;
    private MatcherBuilder matcherBuilder;
    private RequestDefinitionSerializer requestDefinitionSerializer;
    private final boolean asynchronousEventProcessing;
    private Disruptor<LogEntry> disruptor;
    private CoalescingWakeWaitStrategy waitStrategy;
    // Executor that runs the O(n) part of every query (retrieve / verify / clear-scan) OFF the single
    // disruptor consumer thread. The consumer thread both APPENDS log entries and — historically — ran
    // every query to completion on that same thread, so a query's O(n) scan (each entry doing a full
    // cloned request match) blocked ingestion: while a scan ran, published entries backed up and, once
    // the fixed ring filled, were DROPPED (only a WARN-once), losing evidence a later verify needs. The
    // fix keeps only a cheap, consistent SNAPSHOT of the entry collection on the consumer thread (a
    // reference-copy, taken in disruptor-sequence order so it observes exactly what an in-line scan would
    // have — ordering/visibility unchanged) and hands the expensive scan+match+map to this pool, so a
    // long query can no longer starve appends. A bounded fixed pool (CPU parallelism, fixed ceiling) with
    // an unbounded queue: independent queries then run on separate threads rather than serialising on the
    // one consumer. Daemon threads (SchedulerThreadFactory) so they never block JVM shutdown.
    private ExecutorService logQueryExecutor;
    // The ringBufferSize the disruptor was actually built with. An LMAX ring is a fixed
    // power-of-two array sized at construction, so this is the value in force for the lifetime of
    // the disruptor regardless of later configuration mutation — see getRingBufferSizeInForce().
    private int ringBufferSizeInForce;
    // Log events dropped before being recorded, by cause (a full ring, or the in-flight byte cap).
    // Monotonic, mirrored to mock_server_dropped_log_events{reason=...}; each cause also WARNs once.
    private final AtomicLong ringFullDroppedLogEvents = new AtomicLong(0);
    private final AtomicLong inFlightBytesDroppedLogEvents = new AtomicLong(0);
    private final AtomicBoolean droppedLogEventWarned = new AtomicBoolean(false);
    // Dropped log events, by cause, since startup or the last reset()/clear-all — the resettable TAINT
    // that feeds the fail-closed verify path (upperBoundUnprovableAfterEviction), distinct from the
    // monotonic per-reason drop counters above (which must never be reset, as they mirror a Prometheus
    // counter). A drop means an incoming entry was NEVER recorded — the same loss of evidence as a deque
    // eviction — so an upper-bound verify must fail closed rather than pass on it. Kept per cause so the
    // failure names what happened since the reset, not what has ever happened on this server.
    private final AtomicLong ringFullDroppedSinceLogReset = new AtomicLong(0);
    private final AtomicLong inFlightBytesDroppedSinceLogReset = new AtomicLong(0);
    // Bytes of request/response body held by log entries that have been PUBLISHED to the ring but not
    // yet processed by the single consumer (the in-flight backlog). The deque byte budget
    // (maxEventLogSizeInBytes) bounds only what has already been RETAINED after processing; it cannot
    // see the ring, whose min(maxLogEntries,16384) pre-allocated slots each hold a full body. Under
    // sustained large-body ingress faster than the consumer can drain (notably at INFO, where the
    // consumer also renders every entry to the log), that backlog — not the deque — is what exhausts
    // the heap. Tracking it lets add() drop, rather than OOM, once the in-flight bodies would exceed
    // the in-flight cap (configuration.maxEventLogInFlightBytes(), never smaller than the retention
    // budget, so a small retention budget does not drop bursts). Incremented on a successful publish
    // in add(), decremented in processLogEntry as each entry is consumed, by the weight add()
    // memoised: translateTo carries it into the ring slot, so the two sides balance exactly and the
    // counter returns to 0 when idle.
    private final AtomicLong inFlightBytes = new AtomicLong(0);
    // The in-flight byte budget in force, mirrored from configuration.maxEventLogInFlightBytes() at
    // construction and refreshed by applyConfigurationCapacity() so a live PUT /mockserver/configuration
    // change tracks here too. <= 0 disables the in-flight bound (ring bounded by slot count only),
    // matching the deque byte budget's "0 disables" contract (e.g. a native image with no heap ceiling).
    private volatile long maxInFlightBytes;
    private final AtomicBoolean inFlightBytesDropWarned = new AtomicBoolean(false);
    // Set once the event log has evicted at least one entry to stay within maxLogEntries /
    // maxEventLogSizeInBytes, so the WARN below is emitted exactly once rather than per eviction.
    // The authoritative count lives on the deque (eventLog.getEvictedCount()); this is only the
    // "have we told the user yet" latch, and it is reset alongside the deque's counter on clear.
    private final AtomicBoolean evictedLogEntryWarned = new AtomicBoolean(false);
    // The deque's eviction count already added to mock_server_evicted_log_entries, so the counter
    // advances by the entries evicted since, stays monotonic across reset/clear (which zero the deque's
    // count), and costs the non-evicting hot path one volatile read.
    private final AtomicLong evictedCountReportedToMetrics = new AtomicLong(0);
    // Header name added to the in-memory (and, if it ran before disk-write, persisted) copy of a
    // request/response body that was truncated by maxLoggedBodyBytes; its value is the original
    // (pre-truncation) body length in bytes.
    public static final String TRUNCATED_BODY_HEADER = "x-mockserver-body-truncated";
    // Optional per-entry hook invoked (off the matching/forwarding path) for each recorded exchange
    // (FORWARDED_REQUEST or EXPECTATION_RESPONSE) log entry, used to persist recorded requests to
    // disk. Null when disk persistence is disabled.
    private Consumer<LogEntry> recordedRequestConsumer;
    private volatile Runnable recordedRequestFlush;
    // null unless a started extension is a ServeEventListener
    private final org.mockserver.extension.ListenerDispatch serveEventDispatch;

    public MockServerEventLog(Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, boolean asynchronousEventProcessing) {
        this(configuration, mockServerLogger, scheduler, asynchronousEventProcessing, MockServerEventLog::defaultWaitStrategy, null);
    }

    /**
     * @param extensionRegistry the server's extensions, or {@code null} when none are configured; its serve-event
     *                          listeners are told about each answered request
     */
    public MockServerEventLog(Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, boolean asynchronousEventProcessing, org.mockserver.extension.ExtensionRegistry extensionRegistry) {
        this(configuration, mockServerLogger, scheduler, asynchronousEventProcessing, MockServerEventLog::defaultWaitStrategy, extensionRegistry);
    }

    /**
     * @param waitStrategyFactory builds the consumer wait strategy from the ring size; tests pass one that never
     *                            polls, so only an explicit wake can deliver an entry
     */
    MockServerEventLog(Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, boolean asynchronousEventProcessing, IntFunction<CoalescingWakeWaitStrategy> waitStrategyFactory) {
        this(configuration, mockServerLogger, scheduler, asynchronousEventProcessing, waitStrategyFactory, null);
    }

    private MockServerEventLog(Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, boolean asynchronousEventProcessing, IntFunction<CoalescingWakeWaitStrategy> waitStrategyFactory, org.mockserver.extension.ExtensionRegistry extensionRegistry) {
        super(scheduler);
        this.serveEventDispatch = extensionRegistry != null ? extensionRegistry.serveEventDispatch() : null;
        this.configuration = configuration;
        // Bound to this server's Configuration instance (not a static method reference) so
        // redactSecretsInLog set programmatically or via PUT /mockserver/configuration is honoured.
        this.logEntryToRequest = logEntry -> logEntry.getRedactedHttpRequests(configuration);
        this.logEntryToRedactedHttpRequestAndHttpResponse = logEntry -> new LogEventRequestAndResponse()
            .withHttpRequest((HttpRequest) logEntry.getRedactedHttpRequest(configuration))
            .withHttpResponse(logEntry.getRedactedHttpResponse(configuration))
            .withTimestamp(logEntry.getTimestamp());
        this.mockServerLogger = mockServerLogger;
        this.matcherBuilder = new MatcherBuilder(configuration, mockServerLogger);
        this.requestDefinitionSerializer = new RequestDefinitionSerializer(mockServerLogger);
        this.asynchronousEventProcessing = asynchronousEventProcessing;
        this.eventLog = new CircularConcurrentLinkedDeque<>(
            configuration.maxLogEntries(),
            configuration.maxEventLogSizeInBytes(),
            LogEntry::estimatedHeapSize,
            LogEntry::clear);
        this.maxInFlightBytes = configuration.maxEventLogInFlightBytes();
        startRingBuffer(waitStrategyFactory);
    }

    private static CoalescingWakeWaitStrategy defaultWaitStrategy(int ringBufferSize) {
        return new CoalescingWakeWaitStrategy(
            TimeUnit.MILLISECONDS.toNanos(1),
            TimeUnit.MILLISECONDS.toNanos(10),
            Math.max(1, Math.min(256, ringBufferSize / 4)),
            TimeUnit.MILLISECONDS.toNanos(50)
        );
    }

    public void add(LogEntry logEntry) {
        if (isLoadGenerated(logEntry)) {
            // Load-generation self-traffic is kept out of this bounded ring buffer: a running load
            // scenario would otherwise flood it and evict real / LLM traffic that the Traffic, Trace
            // and Optimise views depend on. The run's throughput/latency and SLO samples are recorded
            // client-side by LoadScenarioOrchestrator, so they are unaffected by never being logged here.
            return;
        }
        logEntry.setPort(getPort());
        if (asynchronousEventProcessing) {
            // Bound the in-flight backlog by BYTES, not just by the ring's slot count. The weight is
            // computed once here — before publish clears the source via translateTo — and always
            // tracked (increment on publish, decrement in processLogEntry) INDEPENDENT of the budget,
            // so the counter cannot drift if the in-flight cap is changed at runtime; the budget
            // gates only the drop DECISION below. 0 for a pure diagnostic entry with no HTTP messages
            // (e.g. RUNNABLE/control events), so tracking is a no-op on the overwhelming majority of entries.
            long budget = maxInFlightBytes;
            long inFlightWeight = logEntry.estimatedHeapSize();
            // Would admitting this entry push the in-flight bodies over the budget? (See
            // wouldExceedInFlightBudget: only rejects when something is already in flight, so a single
            // body larger than the whole budget is still admitted — mirroring the deque's "one
            // oversized element is still retained" rule.)
            if (wouldExceedInFlightBudget(budget, inFlightBytes.get(), inFlightWeight)) {
                // In-flight byte budget reached: drop rather than let the ring backlog exhaust the
                // heap. Same observable accounting as a full ring (count + Prometheus counter + a
                // once-only WARN), but named for the byte bound so the remedy points at body size /
                // the budget rather than at ringBufferSize (more slots would hold MORE large bodies,
                // making the OOM worse). Like an eviction/ring-full drop this loses the entry, so a
                // later verify against it cannot prove presence — announced, not silent.
                recordDrop(DropReason.IN_FLIGHT_BYTES);
                if (inFlightBytesDropWarned.compareAndSet(false, true)) {
                    logger.warn("Log event in-flight byte budget reached (" + budget + " bytes, the larger of maxEventLogSizeInBytes and a heap-derived cap) — dropping log events whose request/response bodies would exceed it while they wait to be processed, to bound the ring backlog and avoid running out of memory. The bodies waiting to be logged are arriving faster than they can be processed and recorded (most acute at a verbose log level, which renders every entry). To keep more coverage, cheapest first: (1) lower the log level (e.g. to WARN), which drains the backlog faster and, from DEBUG or TRACE, widens this budget; (2) if you have heap headroom, raise maxEventLogSizeInBytes above this budget, or set it to 0 to bound by ring slot count only. maxLoggedBodyBytes does not help here: it truncates bodies only after they leave this backlog. Dropped events are not retrievable and cannot be verified.");
                }
                // if dropping, only mirror WARN and ERROR to the logger (as the ring-full path does)
                if (logEntry.getLogLevel().toInt() >= Level.WARN.toInt()) {
                    logger.warn("Too many in-flight log event bytes to add log event to ring buffer: " + logEntry);
                }
                return;
            }
            if (!disruptor.getRingBuffer().tryPublishEvent(logEntry)) {
                // ring buffer full: the event is dropped. Make the drop observable — count it,
                // mirror it to Prometheus (no-op when metrics are disabled), and WARN once so the
                // saturation cliff surfaces in the log even for INFO/DEBUG events.
                recordDrop(DropReason.RING_FULL);
                if (droppedLogEventWarned.compareAndSet(false, true)) {
                    logger.warn("Log event ring buffer full — dropping log events because they arrive faster than the single logging thread can record them. If this persists under steady load, lower the log level (e.g. to WARN or ERROR), which skips the per-expectation diagnostic entries that saturate that thread, or keep INFO and set maxLoggedMatchFailures (e.g. to 10) to cap those entries per request; raising ringBufferSize only absorbs short bursts. Dropped events are not retrievable and cannot be verified.");
                }
                // if ring buffer full only write WARN and ERROR to logger
                if (logEntry.getLogLevel().toInt() >= Level.WARN.toInt()) {
                    logger.warn("Too many log events failed to add log event to ring buffer: " + logEntry);
                }
            } else if (inFlightWeight > 0) {
                // Published: this body is now in flight until the consumer processes it (see processLogEntry).
                // Past a quarter of the budget, wake the consumer rather than let the backlog grow while it
                // polls, so coalesced wake-ups never cause a byte-budget drop that prompt processing avoids.
                long inFlight = inFlightBytes.addAndGet(inFlightWeight);
                if (budget > 0 && inFlight > budget / 4) {
                    waitStrategy.wakeConsumer();
                }
            }
        } else {
            try {
                processLogEntry(logEntry);
            } finally {
                flushRecordedRequests();
            }
        }
    }

    public int size() {
        return eventLog.size();
    }

    /**
     * True when this entry's request(s) carry the in-process load-generation marker set by
     * {@link org.mockserver.mock.action.http.LoadScenarioOrchestrator} via
     * {@link HttpRequest#setLoadGenerated(boolean)}, i.e. the entry describes the server's own
     * load-generation traffic and should be kept out of the event log. The marker is an in-process
     * flag, not a wire header, so it never travels to an upstream target and only suppresses logging
     * on the driver that generated the traffic.
     */
    private static boolean isLoadGenerated(LogEntry logEntry) {
        for (RequestDefinition request : logEntry.getHttpRequests()) {
            if (request instanceof HttpRequest && ((HttpRequest) request).isLoadGenerated()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Number of log events dropped before being recorded, for every {@link DropReason}. A non-zero,
     * growing value indicates the event log cannot keep up with the incoming load. Always available
     * (independent of whether Prometheus metrics are enabled); also mirrored, by reason, to the
     * {@code mock_server_dropped_log_events} Prometheus counter when metrics are enabled.
     */
    public long getDroppedLogEventCount() {
        return ringFullDroppedLogEvents.get() + inFlightBytesDroppedLogEvents.get();
    }

    /**
     * Number of log events dropped for {@code reason} since startup (never reset).
     */
    public long getDroppedLogEventCount(DropReason reason) {
        return droppedLogEventCounter(reason).get();
    }

    private AtomicLong droppedLogEventCounter(DropReason reason) {
        return reason == DropReason.RING_FULL ? ringFullDroppedLogEvents : inFlightBytesDroppedLogEvents;
    }

    private AtomicLong droppedSinceLogResetCounter(DropReason reason) {
        return reason == DropReason.RING_FULL ? ringFullDroppedSinceLogReset : inFlightBytesDroppedSinceLogReset;
    }

    private void recordDrop(DropReason reason) {
        droppedLogEventCounter(reason).incrementAndGet();
        droppedSinceLogResetCounter(reason).incrementAndGet();
        Metrics.incrementDroppedLogEvents(reason.metricLabel());
    }

    private void clearDropTaint() {
        ringFullDroppedSinceLogReset.set(0);
        inFlightBytesDroppedSinceLogReset.set(0);
    }

    /**
     * Number of log entries evicted from the event log because it reached {@code maxLogEntries} (or
     * {@code maxEventLogSizeInBytes}), since startup or the last {@link #reset()}.
     * <p>
     * A non-zero value means the log is no longer a complete record: requests that genuinely
     * happened may have been discarded. Verifications that assert an <em>upper</em> bound
     * ({@code never()}, {@code atMost(n)}, and the upper half of {@code exactly}/{@code between})
     * cannot be trusted once this is non-zero, because "not found" may mean "discarded" rather than
     * "never happened" — {@link #verify} therefore fails such verifications instead of passing them.
     * Counts only true evictions: an explicit {@code reset()}/{@code clear()} resets it to zero.
     */
    public long getEvictedLogEntryCount() {
        return eventLog.getEvictedCount();
    }

    /**
     * Re-read the event log's capacity bounds from the {@link Configuration} and resize the backing
     * deque in place, so a {@code maxLogEntries} / {@code maxEventLogSizeInBytes} change made via
     * {@code PUT /mockserver/configuration} actually takes effect on the running log instead of
     * being accepted and ignored. A shrink evicts the oldest entries immediately.
     * <p>
     * Note this deliberately does NOT touch {@code ringBufferSize} — the disruptor's ring is a
     * fixed power-of-two array chosen at construction and cannot be resized without draining
     * in-flight events and rebuilding every handler. See {@link #getRingBufferSizeInForce()}.
     */
    public void applyConfigurationCapacity() {
        eventLog.setMaxSize(configuration.maxLogEntries());
        eventLog.setMaxBytes(configuration.maxEventLogSizeInBytes());
        // The in-flight cap is derived from maxEventLogSizeInBytes, so re-derive it too. A shrink cannot
        // evict what is already in the ring (the ring is not resizable — see startRingBuffer), but it
        // applies to every subsequent publish, so the backlog trends down to the new bound as it drains.
        this.maxInFlightBytes = configuration.maxEventLogInFlightBytes();
    }

    /**
     * The {@code ringBufferSize} actually in force — the value read from the {@link Configuration}
     * when the disruptor was constructed. Because the ring cannot be resized, a later change to
     * {@code configuration.ringBufferSize()} does not move this value; the control plane compares
     * against it to warn that a supplied value will not take effect.
     */
    public int getRingBufferSizeInForce() {
        return ringBufferSizeInForce;
    }

    /**
     * Number of ring-buffer slots currently occupied by published-but-not-yet-consumed log entries
     * (the disruptor consumer backlog). {@code getBufferSize() - remainingCapacity()}, clamped to
     * {@code >= 0}. A value approaching {@link #getRingBufferSizeInForce()} means the single consumer
     * cannot drain as fast as producers publish — the ring is backing up and drops are imminent. Read
     * live from the ring at scrape time; cheap — two reads of the ring's own internal sequences (the
     * {@code disruptor} field itself is a plain reference, not volatile). Backs the
     * {@code mock_server_event_log_ring_occupancy} gauge so a scrape during a run shows the backlog
     * BUILDING rather than only its aftermath (a non-zero {@code mock_server_dropped_log_events}).
     */
    public long getRingBufferOccupancy() {
        final Disruptor<LogEntry> currentDisruptor = disruptor;
        if (currentDisruptor == null) {
            return 0;
        }
        try {
            final long occupancy = currentDisruptor.getRingBuffer().getBufferSize()
                - currentDisruptor.getRingBuffer().remainingCapacity();
            return Math.max(0, occupancy);
        } catch (Exception ignored) {
            // fail-soft: a transient state during startRingBuffer()/shutdown must never break a scrape
            return 0;
        }
    }

    /**
     * The in-flight body-byte budget currently in force ({@code maxEventLogSizeInBytes}); {@code <= 0}
     * means the in-flight bound is disabled. Backs {@code mock_server_event_log_max_in_flight_bytes} so
     * {@link #getInFlightBytes()} can be read against its ceiling on the same scrape.
     */
    public long getMaxInFlightBytes() {
        return maxInFlightBytes;
    }

    /**
     * Number of log entries currently RETAINED in the event log after processing — the live element
     * count of the backing deque. This is the SECOND event-log retention site, distinct from the ring
     * in-flight figures ({@link #getRingBufferOccupancy()} / {@link #getInFlightBytes()}) which count
     * entries published to the disruptor but not yet processed. Backs the
     * {@code mock_server_event_log_retained_entries} gauge so a scrape can tell whether the heap is
     * held by the ring backlog or by the retained log. Cheap (one atomic read — see
     * {@link CircularConcurrentLinkedDeque#size()}).
     */
    public long getRetainedEntryCount() {
        // same value as size() — a domain name for the retained_entries gauge; keep both in step.
        return eventLog.size();
    }

    /**
     * Summed request/response body weight of the log entries currently RETAINED after processing — the
     * running byte total of the backing deque. This is the post-processing companion to
     * {@link #getInFlightBytes()} (the ring's in-flight bytes): the same body bytes move from in-flight
     * to retained as the consumer drains the ring. Backs {@code mock_server_event_log_retained_bytes}
     * so a scrape can attribute heap growth to the retained log rather than the ring.
     * <p>
     * Reported whether or not the byte budget is enabled: the event log always supplies a weigher, and
     * {@code maxEventLogSizeInBytes <= 0} disables byte EVICTION, not byte accounting. The figure is
     * therefore still live — and most worth watching — when the budget is off, since nothing is then
     * capping what the log retains. Cheap (one atomic read).
     */
    public long getRetainedBytes() {
        return eventLog.getTotalBytes();
    }

    /**
     * The retained byte budget in force for the event log ({@code maxEventLogSizeInBytes}); {@code <= 0}
     * means the retained byte bound is disabled and only {@code maxLogEntries} applies. Backs
     * {@code mock_server_event_log_max_retained_bytes} so {@link #getRetainedBytes()} can be read
     * against its ceiling on the same scrape.
     */
    public long getMaxRetainedBytes() {
        return eventLog.getMaxBytes();
    }

    /**
     * The retained entry-count cap in force for the event log ({@code maxLogEntries}). Backs
     * {@code mock_server_event_log_max_retained_entries} so {@link #getRetainedEntryCount()} can be
     * read against its ceiling on the same scrape.
     */
    public long getMaxRetainedEntries() {
        return eventLog.getMaxSize();
    }

    private void startRingBuffer(IntFunction<CoalescingWakeWaitStrategy> waitStrategyFactory) {
        ringBufferSizeInForce = configuration.ringBufferSize();
        // Bounded pool for running query scans off the consumer thread (see logQueryExecutor field), with
        // an unbounded queue so bursts of concurrent queries queue rather than being rejected and never
        // touch the disruptor consumer thread. Sized max(2, cores/2): at least 2 so one slow query cannot
        // head-of-line-block every other query/verify, but only up to half the cores — a query's dominant
        // cost is per-entry request cloning during the match, which is memory-bandwidth bound, so running
        // more scans in parallel does not raise query throughput (it can lower it via allocation/GC
        // pressure) and would only steal cores from request serving and ingestion. Query parallelism is a
        // non-goal here; keeping the consumer free to append (no dropped events) is the point.
        int queryThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        logQueryExecutor = new ThreadPoolExecutor(
            queryThreads, queryThreads,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new Scheduler.SchedulerThreadFactory("EventLog-Query")
        );
        waitStrategy = waitStrategyFactory.apply(ringBufferSizeInForce);
        disruptor = new Disruptor<>(LogEntry::new, ringBufferSizeInForce, new Scheduler.SchedulerThreadFactory("EventLog"), ProducerType.MULTI, waitStrategy);

        final ExceptionHandler<LogEntry> errorHandler = new ExceptionHandler<LogEntry>() {
            @Override
            public void handleEventException(Throwable ex, long sequence, LogEntry logEntry) {
                logger.error("exception handling log entry in log ring buffer, for log entry: " + logEntry, ex);
            }

            @Override
            public void handleOnStartException(Throwable ex) {
                logger.error("exception starting log ring buffer", ex);
            }

            @Override
            public void handleOnShutdownException(Throwable ex) {
                logger.error("exception during shutdown of log ring buffer", ex);
            }
        };
        disruptor.setDefaultExceptionHandler(errorHandler);

        disruptor.handleEventsWith((logEntry, sequence, endOfBatch) -> {
            try {
                if (logEntry.getType() != RUNNABLE) {
                    processLogEntry(logEntry);
                } else {
                    logEntry.getConsumer().run();
                    logEntry.clear();
                }
            } finally {
                if (endOfBatch) {
                    flushRecordedRequests();
                }
            }
        });

        disruptor.start();
    }

    /**
     * Run a query's O(n) scan without starving log ingestion: dispatch onto the single disruptor
     * consumer thread only long enough to take a cheap, consistent reference-copy of the retained
     * entries, then run the supplied {@code scan} — the expensive part (predicate
     * filtering plus the per-entry cloned request match) — OFF that thread on {@link #logQueryExecutor}.
     * <p>
     * Ordering/visibility are unchanged from running the whole query in-line on the consumer: the
     * snapshot is taken in disruptor-sequence order, so it observes exactly the entries published before
     * this query's RUNNABLE — the same set the old in-line scan saw. {@code verify()} still calls
     * {@link #drainDisruptor()} first, so the snapshot still includes everything published before the
     * verify. The snapshot is a new list of the SAME {@link LogEntry} references; those entries are
     * effectively immutable after {@code processLogEntry}'s {@code cloneAndClear} (the only later mutation
     * is a {@code clear()} tombstone flag, a benign concurrent-clear race that was already undefined in
     * ordering), so the off-thread scan reads stable state.
     *
     * @param descending capture the snapshot newest-first (for the reverse-order UI / unmatched queries)
     * @param scan       the O(n) body to run off the consumer thread against the snapshot
     */
    private void querySnapshot(boolean descending, Consumer<List<LogEntry>> scan) {
        publishControl(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> {
                final List<LogEntry> snapshot;
                if (descending) {
                    snapshot = new ArrayList<>(eventLog.size());
                    eventLog.descendingIterator().forEachRemaining(snapshot::add);
                } else {
                    // reference-only copy of the deque, taken on the consumer thread where no concurrent
                    // append can occur, so it is a consistent point-in-time view; cheap relative to the
                    // per-entry match that follows off-thread.
                    snapshot = new ArrayList<>(eventLog);
                }
                runOffConsumer(() -> scan.accept(snapshot));
            })
        );
    }

    /**
     * Run {@code task} on {@link #logQueryExecutor} (off the disruptor consumer thread). Exceptions are
     * logged, not propagated, matching the disruptor exception handler that previously swallowed-and-logged
     * a throwing query body. If the executor has been shut down (during {@link #stop()}), fall back to
     * running the task inline on the current (consumer) thread so a waiting consumer / verify future still
     * completes rather than hanging.
     */
    private void runOffConsumer(Runnable task) {
        try {
            logQueryExecutor.execute(() -> runQueryTask(task));
        } catch (RejectedExecutionException shuttingDown) {
            runQueryTask(task);
        }
    }

    private static void runQueryTask(Runnable task) {
        try {
            task.run();
        } catch (Throwable throwable) {
            logger.error("exception while scanning event log snapshot for a query", throwable);
        }
    }

    /**
     * Register a hook invoked once per recorded exchange (FORWARDED_REQUEST or EXPECTATION_RESPONSE)
     * log entry, off the request-matching / forwarding hot path, so a recorded proxied or mocked
     * exchange can be persisted to disk without coupling file I/O into this class. The hook receives
     * the entry with FULL (un-truncated) bodies because {@link #processLogEntry} invokes it BEFORE
     * applying {@code maxLoggedBodyBytes} truncation.
     */
    public void setRecordedRequestConsumer(Consumer<LogEntry> recordedRequestConsumer) {
        setRecordedRequestConsumer(recordedRequestConsumer, null);
    }

    /**
     * As {@link #setRecordedRequestConsumer(Consumer)}, plus a {@code flush} run once the consumer thread
     * reaches the end of a batch (the ring is momentarily drained) — or after each entry when event
     * processing is synchronous — so the hook can buffer writes and hand them to the OS once per batch.
     */
    public void setRecordedRequestConsumer(Consumer<LogEntry> recordedRequestConsumer, Runnable flush) {
        this.recordedRequestFlush = flush;
        this.recordedRequestConsumer = recordedRequestConsumer;
    }

    private void flushRecordedRequests() {
        Runnable flush = recordedRequestFlush;
        if (flush != null) {
            try {
                flush.run();
            } catch (Throwable throwable) {
                logger.error("exception flushing recorded requests", throwable);
            }
        }
    }

    /**
     * Re-inject a recorded request/response pair loaded from a persisted NDJSON archive back into the
     * live event log so it is retrievable exactly like an in-memory recording (via
     * {@code retrieveRecordedRequests} / {@code retrieveRequestResponses} and the derived export
     * formats). The entry is logged as a FORWARDED_REQUEST and marked
     * {@link LogEntry#setSkipRecordedRequestPersistence(boolean)} so re-loading an archive is
     * idempotent and never appends the same exchanges back to the (possibly same) file.
     */
    public void importRecordedRequestResponse(HttpRequest httpRequest, HttpResponse httpResponse) {
        if (httpRequest == null) {
            return;
        }
        add(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(httpRequest)
            .setHttpResponse(httpResponse)
            .setExpectation(httpRequest, httpResponse)
            .setSkipRecordedRequestPersistence(true)
            .setMessageFormat("re-imported recorded request:{}and response:{}from persisted archive")
            .setArguments(httpRequest, httpResponse));
    }

    private void processLogEntry(LogEntry logEntry) {
        // This entry is leaving the in-flight backlog. Decrement by the SAME weight add() incremented by,
        // before cloneAndClear clears the slot. It must be the memo translateTo carried into the slot, NOT
        // a recompute: a sibling entry sharing this request (RECEIVED_REQUEST + EXPECTATION_RESPONSE) may
        // already have released its body's decoded String, shrinking a recompute and leaking phantom bytes.
        // Unconditional on the asynchronous path, matching add(); the synchronous path never published.
        if (asynchronousEventProcessing) {
            long inFlightWeight = logEntry.estimatedHeapSize();
            if (inFlightWeight > 0) {
                inFlightBytes.addAndGet(-inFlightWeight);
            }
        }
        logEntry = logEntry.cloneAndClear();
        // Disk capture runs FIRST so persisted recorded requests keep full fidelity even when
        // maxLoggedBodyBytes truncates the in-memory copy below. (Recommended combo: disk-capture ON
        // with maxLoggedBodyBytes=0, so memory is bounded by the byte budget and disk is complete.)
        // Both mocked (EXPECTATION_RESPONSE) and forwarded/proxied (FORWARDED_REQUEST) exchanges are
        // captured, so the archive is a complete record of served traffic, not proxy traffic only.
        // Entries injected by the re-import path carry skipRecordedRequestPersistence=true so reloading
        // an archive never appends the same exchanges back to the (possibly same) file.
        if (recordedRequestConsumer != null
            && !logEntry.isSkipRecordedRequestPersistence()
            && (logEntry.getType() == FORWARDED_REQUEST || logEntry.getType() == EXPECTATION_RESPONSE)) {
            recordedRequestConsumer.accept(logEntry);
        }
        // Secondary memory valve: cap the body bytes retained per entry. Builds NEW copies (never
        // mutates the shared live request/response) because LogEntry.clone() copies them by reference.
        if (configuration.maxLoggedBodyBytes() > 0) {
            truncateBodiesForLog(logEntry);
        }
        // Render BEFORE releasing derived forms: getMessage decodes each text body, and releasing
        // afterwards drops that decode instead of re-caching it on the retained entry. A render failure
        // must not lose the entry.
        try {
            writeToSystemOut(logger, logEntry, configuration);
        } catch (Exception renderFailure) {
            logger.error("exception writing log entry to system out", renderFailure);
        }
        // The retained entry no longer needs the decoded String view its bodies cached while matching (or
        // that the render above re-derived): it is faithfully re-derivable from the canonical raw bytes on
        // the next render/verify. Drop it BEFORE add() so the weight add() computes reflects the released
        // body (roughly raw bytes plus the structural constants) rather than double-counting the second copy.
        logEntry.releaseDerivedForms();
        // here, not earlier: the event's weight memoises the entry's estimatedHeapSize, which add() then reuses, so
        // it must be taken after truncation and releaseDerivedForms; and before add(), whose eviction may clear it
        if (serveEventDispatch != null) {
            serveEventDispatch.served(logEntry);
        }
        // add() weighs the (possibly truncated) entry via LogEntry::estimatedHeapSize for the byte
        // budget — truncation has already run, and the estimate is computed lazily inside add().
        eventLog.add(logEntry);
        // Make eviction observable: the counter advances by the entries evicted since the last write
        // (two volatile reads when nothing was evicted), and the WARN is built at most ONCE per log
        // instance (the AtomicBoolean latch), so its string work stays off the steady-state path.
        long evicted = eventLog.getEvictedCount();
        long reported = evictedCountReportedToMetrics.get();
        if (evicted != reported && evictedCountReportedToMetrics.compareAndSet(reported, evicted) && evicted > reported) {
            Metrics.incrementEvictedLogEntries(evicted - reported);
        }
        if (evicted > 0 && evictedLogEntryWarned.compareAndSet(false, true)) {
            logger.warn(buildEvictionWarning(
                eventLog.getByteEvictedCount() > 0,
                configuration.maxLogEntries(),
                configuration.maxEventLogSizeInBytes(),
                configuration.logLevel()));
        }
        notifyListeners(this, false);
    }

    /**
     * Decide whether admitting an entry of {@code incomingWeight} body bytes would push the in-flight
     * (published-but-not-yet-processed) backlog over {@code budget}. Kept as a pure static function so
     * the boundary rules are unit-testable without driving the disruptor.
     * <ul>
     *   <li>{@code budget <= 0} — the in-flight byte bound is disabled (ring bounded by slot count
     *   only); never rejects.</li>
     *   <li>{@code incomingWeight <= 0} — a body-less entry (e.g. a control/RUNNABLE or a bodyless
     *   diagnostic) costs nothing to hold in flight; never rejects.</li>
     *   <li>{@code inFlight <= 0} — nothing is in flight, so a single entry is always admitted even if
     *   its body alone exceeds the budget (never reject into an empty backlog), matching the deque's
     *   "one oversized element is still retained" rule.</li>
     * </ul>
     * Otherwise rejects once {@code inFlight + incomingWeight} exceeds {@code budget}.
     */
    static boolean wouldExceedInFlightBudget(long budget, long inFlight, long incomingWeight) {
        return budget > 0 && incomingWeight > 0 && inFlight > 0 && inFlight + incomingWeight > budget;
    }

    /**
     * Body bytes currently in flight (published to the ring, not yet processed). After the disruptor
     * has drained this returns to zero, proving the add()/processLogEntry accounting balances (relied
     * on by tests). Also read live at scrape time to back the {@code mock_server_event_log_in_flight_bytes}
     * gauge — the off-outside-view answer to "is the event log ring backing up?" that builds 261/264
     * could not get from k6's side. Cheap (one atomic read).
     */
    public long getInFlightBytes() {
        return inFlightBytes.get();
    }

    /**
     * Build the once-per-server WARN emitted when the event log first evicts. Kept as a pure static
     * function (no field access) so it is unit-testable and so the (one-off) string work never touches
     * the hot path. The message is actionable without opening documentation: it names which bound was
     * hit and its current value, states the effect on verification, and orders remedies cheapest-first,
     * tailored to the bound.
     *
     * <p><strong>The verbosity remedy is only offered for the count bound, and it is safe.</strong> In
     * {@link org.mockserver.logging.MockServerLogger#logEvent}, the entry types a verification reads —
     * {@code RECEIVED_REQUEST} (request verify) and {@code EXPECTATION_RESPONSE}/{@code FORWARDED_REQUEST}
     * (response verify) — are added to the event log at EVERY log level; only lower-priority diagnostics
     * such as {@code EXPECTATION_NOT_MATCHED} are gated by the level. So lowering the level reduces the
     * number of entries retained per request without dropping anything {@code verify} depends on — it
     * trades log detail, never verification correctness ({@code maxLoggedMatchFailures} caps the same
     * diagnostics without changing the level). That lever does NOT help the byte bound: the
     * request/response bodies dominating the byte budget live on the always-retained entry types, so for
     * a byte-bound eviction the message points at {@code maxLoggedBodyBytes} / the budget instead.
     *
     * @param byteBoundHit  true when the byte budget ({@code maxEventLogSizeInBytes}) drove the first
     *                      eviction; false when the entry-count bound ({@code maxLogEntries}) did
     * @param maxLogEntries the effective {@code maxLogEntries} for this server
     * @param maxEventLogSizeInBytes the effective {@code maxEventLogSizeInBytes} for this server
     * @param logLevel      the effective log level for this server (named in the verbosity remedy)
     */
    static String buildEvictionWarning(boolean byteBoundHit, int maxLogEntries, long maxEventLogSizeInBytes, Level logLevel) {
        String bound = byteBoundHit
            ? "byte budget reached (maxEventLogSizeInBytes=" + maxEventLogSizeInBytes + " bytes) — the retained request/response bodies exceeded it"
            : "entry-count limit reached (maxLogEntries=" + maxLogEntries + ")";
        StringBuilder message = new StringBuilder()
            .append("MockServer event log full — now discarding the oldest entries, so it is no longer a complete record; ")
            .append(bound).append(". ")
            .append("Verifications asserting an upper bound — never(), atMost(n), exactly(n), once() and between(a,b) — can no longer prove absence ")
            .append("and (with the default failVerificationOnEvictedLog=true) will now FAIL rather than pass on discarded evidence; ")
            .append("atLeast(n) and the bare verify(request) [atLeast(1)] are unaffected. ")
            .append("To keep more coverage, cheapest first: ");
        if (byteBoundHit) {
            message
                .append("(1) record smaller bodies — set maxLoggedBodyBytes to truncate large bodies, which dominate this budget ")
                .append("(bodies are retained at every log level, so lowering the log level will NOT free this); ")
                .append("(2) if you have heap headroom, raise the budget maxEventLogSizeInBytes (currently ").append(maxEventLogSizeInBytes).append(" bytes), or set it to 0 to bound by count only; ")
                .append("(3) clear/reset the event log between tests.");
        } else {
            message
                .append("(1) record less — at log level ").append(logLevel).append(" each request also logs a diagnostic entry per non-matching expectation; ")
                .append("lowering the log level (e.g. to WARN) stops recording those and cuts entries retained per request, and does NOT affect what verify can find ")
                .append("(received requests and mocked/forwarded responses are recorded at every level; ")
                .append("note: the default byte budget is smaller at INFO and WARN than at DEBUG and TRACE, so raise maxEventLogSizeInBytes if it then binds); ")
                .append("or, to keep this log level, set maxLoggedMatchFailures (e.g. to 10) to cap those entries per request; ")
                .append("(2) if you have heap headroom, raise the limit maxLogEntries (currently ").append(maxLogEntries).append("); ")
                .append("(3) clear/reset the event log between tests.");
        }
        return message.toString();
    }

    /**
     * Replace the request and/or response on the (already-cloned) log entry with body-truncated COPIES
     * when their raw body exceeds {@code maxLoggedBodyBytes}. The original request/response objects are
     * shared by reference with the live proxied exchange (LogEntry.clone() copies them by reference), so
     * we must build fresh clones — {@link HttpRequest#clone()} / {@link HttpResponse#clone()} also clone
     * the headers map, so adding the truncation marker header never mutates the live objects. The header
     * value is the original body length so a reader can tell the body was clipped.
     */
    private void truncateBodiesForLog(LogEntry logEntry) {
        int maxLoggedBodyBytes = configuration.maxLoggedBodyBytes();
        // one cut copy per original, so an expectation and its clone (which share their request and response)
        // keep one copy between them, the one the weigher charges with the named expectation
        Map<Object, Object> cutCopies = new IdentityHashMap<>();
        RequestDefinition requestDefinition = logEntry.getHttpRequest();
        if (requestDefinition instanceof HttpRequest || requestDefinition instanceof BinaryRequestDefinition) {
            RequestDefinition truncated = (RequestDefinition) cutOnce(requestDefinition, maxLoggedBodyBytes, cutCopies);
            if (truncated != requestDefinition) {
                logEntry.setHttpRequest(truncated).replaceQuoted(requestDefinition, truncated);
            }
        }
        HttpResponse httpResponse = logEntry.getHttpResponse();
        HttpResponse truncatedResponse = (HttpResponse) cutOnce(httpResponse, maxLoggedBodyBytes, cutCopies);
        if (truncatedResponse != httpResponse) {
            logEntry.setHttpResponse(truncatedResponse).replaceQuoted(httpResponse, truncatedResponse);
        }
        // An expectation or action the entry names or quotes, and a matcher's "because", copy whole bodies too;
        // once the expectation is removed only the log holds them.
        logEntry.boundQuotedBodies(quoted -> cutOnce(quoted, maxLoggedBodyBytes, cutCopies), maxLoggedBodyBytes);
    }

    private static Object cutOnce(Object quoted, int maxLoggedBodyBytes, Map<Object, Object> cutCopies) {
        if (quoted == null) {
            return null;
        }
        Object cut = cutCopies.get(quoted);
        if (cut == null) {
            if (quoted instanceof HttpRequest) {
                cut = truncatedForLog((HttpRequest) quoted, maxLoggedBodyBytes);
            } else if (quoted instanceof BinaryRequestDefinition) {
                cut = truncatedForLog((BinaryRequestDefinition) quoted, maxLoggedBodyBytes);
            } else if (quoted instanceof HttpResponse) {
                cut = truncatedForLog((HttpResponse) quoted, maxLoggedBodyBytes);
            } else if (quoted instanceof Expectation) {
                Expectation expectation = (Expectation) quoted;
                RequestDefinition request = expectation.getHttpRequest();
                Object cutRequest = request instanceof HttpRequest ? cutOnce(request, maxLoggedBodyBytes, cutCopies) : request;
                Object cutResponse = cutOnce(expectation.getHttpResponse(), maxLoggedBodyBytes, cutCopies);
                cut = cutRequest == request && cutResponse == expectation.getHttpResponse()
                    ? expectation
                    : expectation.cloneWith((RequestDefinition) cutRequest, (HttpResponse) cutResponse);
            } else {
                cut = quoted;
            }
            cutCopies.put(quoted, cut);
        }
        return cut;
    }

    private static HttpRequest truncatedForLog(HttpRequest httpRequest, int maxLoggedBodyBytes) {
        byte[] body = httpRequest.getBodyAsRawBytes();
        if (body == null || body.length <= maxLoggedBodyBytes) {
            return httpRequest;
        }
        return httpRequest
            .clone()
            .withBody(Arrays.copyOf(body, maxLoggedBodyBytes))
            .withHeader(TRUNCATED_BODY_HEADER, String.valueOf(body.length));
    }

    private static BinaryRequestDefinition truncatedForLog(BinaryRequestDefinition binaryRequest, int maxLoggedBodyBytes) {
        byte[] bytes = binaryRequest.getBinaryData();
        if (bytes == null || bytes.length <= maxLoggedBodyBytes) {
            return binaryRequest;
        }
        BinaryRequestDefinition truncated = binaryRequest.shallowClone().withBinaryData(Arrays.copyOf(bytes, maxLoggedBodyBytes));
        truncated.withLogCorrelationId(binaryRequest.getLogCorrelationId());
        return truncated;
    }

    private static HttpResponse truncatedForLog(HttpResponse httpResponse, int maxLoggedBodyBytes) {
        byte[] body = httpResponse == null ? null : httpResponse.getBodyAsRawBytes();
        if (body == null || body.length <= maxLoggedBodyBytes) {
            return httpResponse;
        }
        return httpResponse
            .clone()
            .withBody(Arrays.copyOf(body, maxLoggedBodyBytes))
            .withHeader(TRUNCATED_BODY_HEADER, String.valueOf(body.length));
    }

    /**
     * Publish an entry a caller is waiting on, waking the consumer rather than leaving it to the next poll
     * of {@link CoalescingWakeWaitStrategy}.
     */
    private void publishControl(LogEntry controlEntry) {
        disruptor.publishEvent(controlEntry);
        waitStrategy.wakeConsumer();
    }

    private void drainDisruptor() {
        if (asynchronousEventProcessing) {
            CountDownLatch latch = new CountDownLatch(1);
            publishControl(new LogEntry()
                .setType(RUNNABLE)
                .setConsumer(latch::countDown)
            );
            try {
                if (!latch.await(2, SECONDS)) {
                    logger.warn("disruptor drain timed out after 2 seconds before verification, results may be incomplete");
                }
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void stop() {
        try {
            notifyListeners(this, true);
            // cancel any pending coalesced (debounced) listener notification so the scheduled
            // task does not outlive the log — the synchronous notifyListeners above already
            // flushed the final state to every listener.
            stopNotifications();
            eventLog.clear();
            waitStrategy.wakeConsumer();
            disruptor.shutdown(2, SECONDS);
            // shutdown's halt signal does not wake a polling consumer; wake it so the thread exits now
            waitStrategy.wakeConsumer();
            // Shut down the query pool AFTER draining the disruptor: any query RUNNABLE still in the ring
            // gets to submit its scan, and once the pool is shut down a late submit falls back to running
            // inline (see runOffConsumer). Daemon threads, so this never blocks JVM shutdown.
            logQueryExecutor.shutdown();
        } catch (Throwable throwable) {
            if (!(throwable instanceof com.lmax.disruptor.TimeoutException)) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    writeToSystemOut(logger, new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("exception while shutting down log ring buffer")
                        .setThrowable(throwable)
                    );
                }
            }
        }
    }

    public void reset() {
        CompletableFuture<String> future = new CompletableFuture<>();
        publishControl(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> {
                // clear() also zeroes the deque's eviction counter; re-arm the warn-once latch so a
                // fresh round of eviction after this reset is reported again. Do the same for the drop
                // taint and its warn latches so a suite that reset()s between tests is not left
                // permanently unable to prove absence (or silent) after one round of drops.
                eventLog.clear();
                evictedLogEntryWarned.set(false);
                evictedCountReportedToMetrics.set(0);
                clearDropTaint();
                inFlightBytesDropWarned.set(false);
                droppedLogEventWarned.set(false);
                future.complete("done");
                notifyListeners(this, false);
            })
        );
        try {
            future.get(2, SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException ignore) {
        }
    }

    public void clear(RequestDefinition requestDefinition) {
        CompletableFuture<String> future = new CompletableFuture<>();
        final boolean markAsDeletedOnly = mockServerLogger.isEnabledForInstance(Level.INFO);
        publishControl(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> {
                String logCorrelationId = UUIDService.getNonSecureUUID();
                // A null filter means "clear everything". The previous code built a fresh empty
                // request().withLogCorrelationId(uuid) matcher for this case, but the unique
                // correlation id made it miss the matcher LRU cache on every clear, forcing an
                // uncached matcher rebuild — and an empty matcher matches every entry that carries
                // at least one request. So short-circuit the whole scan rather than building and
                // running a matcher. Note getHttpRequests() returns a length-0 array (never null)
                // for request-less entries (SERVER_CONFIGURATION, TRACE/WARN/EXCEPTION/CLEARED logged
                // without a request); the old per-request loop never set matches for those, so they
                // SURVIVED clear(null). The `length > 0` guard reproduces that exactly while keeping
                // the perf win. When a real filter is supplied the matcher is built once (cached by
                // the filter) and matched fail-fast (single-arg matches => context == null => no
                // MatchDifference allocation) exactly as before.
                // an empty HTTP filter too: a request one would not match, such as a DNS query, must be cleared
                final boolean clearEverything = RequestMatchers.isUnfiltered(requestDefinition);
                HttpRequestMatcher requestMatcher = clearEverything ? null : matcherBuilder.transformsToMatcher(requestDefinition);
                // Only the single Disruptor handler thread mutates eventLog (add via processLogEntry,
                // removeItem/eviction here), and this consumer runs on that same thread, so there is no
                // concurrent writer to guard against — iterate the deque directly rather than copying up
                // to maxLogEntries entries into a LinkedList first. eventLog's ConcurrentLinkedDeque
                // iterator is weakly consistent and tolerates the removeItem (super.remove) calls below.
                for (LogEntry logEntry : eventLog) {
                    if (markAsDeletedOnly && logEntry.isDeleted()) {
                        // already tombstoned by an earlier clear — skip the expensive matcher so
                        // repeated clear cycles do not re-match the whole accumulated log (#2359)
                        continue;
                    }
                    RequestDefinition[] requests = logEntry.getHttpRequests();
                    boolean matches = false;
                    if (clearEverything) {
                        matches = requests.length > 0;
                    } else if (requests != null) {
                        for (RequestDefinition request : requests) {
                            if (requestMatcher.matches(request.cloneWithLogCorrelationId())) {
                                matches = true;
                            }
                        }
                    } else {
                        matches = true;
                    }
                    if (matches) {
                        if (markAsDeletedOnly) {
                            logEntry.setDeleted(true);
                        } else {
                            eventLog.removeItem(logEntry);
                        }
                    }
                }
                if (clearEverything) {
                    // "Clear everything" declares the whole recorded history irrelevant, exactly as
                    // reset() does, so it must also clear the eviction taint — otherwise a suite that
                    // uses clear() rather than reset() between tests would stay permanently unable to
                    // prove absence after the log rolled over once. Note this path TOMBSTONES entries
                    // when logging at INFO rather than emptying the deque, so eventLog.clear() (which
                    // resets the counter itself) is not called here and the counter is reset directly.
                    // A FILTERED clear deliberately does not reset it: removing some entries says
                    // nothing about the other evidence eviction already destroyed.
                    eventLog.resetEvictedCount();
                    evictedLogEntryWarned.set(false);
                    evictedCountReportedToMetrics.set(0);
                    clearDropTaint();
                    inFlightBytesDropWarned.set(false);
                    droppedLogEventWarned.set(false);
                }
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(CLEARED)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(logCorrelationId)
                            .setHttpRequest(requestDefinition)
                            .setMessageFormat("cleared logs that match:{}")
                            .setArguments((requestDefinition == null ? "{}" : requestDefinition))
                    );
                }
                future.complete("done");
                notifyListeners(this, false);
            })
        );
        try {
            future.get(2, SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException ignore) {
        }
    }

    public void retrieveMessageLogEntries(RequestDefinition requestDefinition, Consumer<List<LogEntry>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            notDeletedPredicate,
            (Stream<LogEntry> logEventStream) -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveMessageLogEntriesIncludingDeleted(RequestDefinition requestDefinition, Consumer<List<LogEntry>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            allPredicate,
            (Stream<LogEntry> logEventStream) -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveRequestLogEntries(RequestDefinition requestDefinition, Consumer<List<LogEntry>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            recordedRequestPredicate(requestDefinition, requestLogPredicate),
            (Stream<LogEntry> logEventStream) -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveRequests(Verification verification, String logCorrelationId, Consumer<List<RequestDefinition>> listConsumer) {
        if (verification.getExpectationId() != null) {
            retrieveLogEntries(
                Collections.singletonList(verification.getExpectationId().getId()),
                answeredRequestLogPredicate,
                logEntryToRequest,
                logEventStream -> listConsumer.accept(
                    logEventStream
                        .filter(Objects::nonNull)
                        .flatMap(Arrays::stream)
                        .collect(Collectors.toList())
                )
            );
        } else {
            // When a disposition filter is set, count requests by how they were handled
            // (FORWARDED_REQUEST or EXPECTATION_RESPONSE) rather than all RECEIVED_REQUEST entries.
            Predicate<LogEntry> typePredicate = requestLogPredicate;
            if (verification.getDisposition() != null) {
                typePredicate = verification.getDisposition() == Disposition.FORWARDED
                    ? forwardedRequestLogPredicate
                    : mockedRequestLogPredicate;
            }
            retrieveLogEntries(
                verification.getHttpRequest().withLogCorrelationId(logCorrelationId),
                recordedRequestPredicate(verification.getHttpRequest(), typePredicate),
                logEntryToRequest,
                logEventStream -> listConsumer.accept(
                    logEventStream
                        .filter(Objects::nonNull)
                        .flatMap(Arrays::stream)
                        .collect(Collectors.toList())
                )
            );
        }
    }

    public void retrieveAllRequests(boolean matchingExpectationsOnly, Consumer<List<RequestDefinition>> listConsumer) {
        if (matchingExpectationsOnly) {
            retrieveLogEntries(
                (List<String>) null,
                answeredRequestLogPredicate,
                logEntryToRequest,
                logEventStream -> listConsumer.accept(
                    logEventStream
                        .filter(Objects::nonNull)
                        .flatMap(Arrays::stream)
                        .collect(Collectors.toList())
                )
            );
        } else {
            retrieveLogEntries(
                (RequestDefinition) null,
                requestLogPredicate,
                logEntryToRequest,
                logEventStream -> listConsumer.accept(
                    logEventStream
                        .filter(Objects::nonNull)
                        .flatMap(Arrays::stream)
                        .collect(Collectors.toList())
                )
            );
        }
    }

    private void retrieveAllRecordedRequests(boolean matchingExpectationsOnly, Consumer<List<VerificationFailureSummary.RecordedRequest>> listConsumer) {
        retrieveAllRecordedRequests(matchingExpectationsOnly, logEntryToRequest, listConsumer);
    }

    /**
     * @param toRequests {@link LogEntry#getHttpRequests()} for the requests as received, which a sequence must match
     *                   on, as redaction masks what a step may match; else the redacted copies a failure shows
     */
    private void retrieveAllRecordedRequests(boolean matchingExpectationsOnly, Function<LogEntry, RequestDefinition[]> toRequests, Consumer<List<VerificationFailureSummary.RecordedRequest>> listConsumer) {
        Function<LogEntry, Stream<VerificationFailureSummary.RecordedRequest>> toRecordedRequests = logEntry -> {
            RequestDefinition[] requests = toRequests.apply(logEntry);
            long arrivedAtMillis = logEntry.getEpochTime();
            return requests == null ? Stream.empty() : Arrays.stream(requests).map(request -> new VerificationFailureSummary.RecordedRequest(request, arrivedAtMillis));
        };
        Consumer<Stream<Stream<VerificationFailureSummary.RecordedRequest>>> collector = logEventStream -> listConsumer.accept(
            logEventStream
                .flatMap(Function.identity())
                .collect(Collectors.toList())
        );
        if (matchingExpectationsOnly) {
            retrieveLogEntries((List<String>) null, answeredRequestLogPredicate, toRecordedRequests, collector);
        } else {
            retrieveLogEntries((RequestDefinition) null, requestLogPredicate, toRecordedRequests, collector);
        }
    }

    /**
     * The request-response pairs response verification counts, each with the time it was logged. The
     * pairs are raw, never redacted, so enabling redaction cannot change a verification pass/fail result.
     */
    private void retrieveRecordedExchanges(RequestDefinition requestDefinition, Consumer<List<VerificationFailureSummary.RecordedExchange>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            responseVerificationLogPredicate,
            logEntry -> new VerificationFailureSummary.RecordedExchange(logEntryToHttpRequestAndHttpResponse.apply(logEntry), logEntry.getEpochTime()),
            logEventStream -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    /**
     * For a verification by disposition, counts the recorded requests that match its request but were
     * handled the other way (forwarded instead of mocked, or the reverse); 0 when not needed, without a query.
     */
    private void countOtherDisposition(Verification verification, String logCorrelationId, boolean needed, IntConsumer countConsumer) {
        if (!needed || verification.getDisposition() == null || !configuration.detailedVerificationFailures() || !(verification.getHttpRequest() instanceof HttpRequest)) {
            countConsumer.accept(0);
            return;
        }
        retrieveLogEntries(
            verification.getHttpRequest().withLogCorrelationId(logCorrelationId),
            verification.getDisposition() == Disposition.FORWARDED ? mockedRequestLogPredicate : forwardedRequestLogPredicate,
            logEntryToRequest,
            logEventStream -> countConsumer.accept((int) logEventStream.filter(Objects::nonNull).mapToLong(requests -> requests.length).sum())
        );
    }

    public void retrieveAllRequests(List<String> expectationIds, Consumer<List<RequestAndExpectationId>> listConsumer) {
        retrieveLogEntries(
            expectationIds,
            answeredRequestLogPredicate,
            logEntry -> new RequestAndExpectationId(logEntry.getHttpRequest(), logEntry.getExpectationId()),
            logEventStream -> listConsumer.accept(
                logEventStream
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())
            )
        );
    }

    public void retrieveRequests(RequestDefinition requestDefinition, Consumer<List<RequestDefinition>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            recordedRequestPredicate(requestDefinition, requestLogPredicate),
            logEntryToRequest,
            logEventStream -> listConsumer.accept(
                logEventStream
                    .filter(Objects::nonNull)
                    .flatMap(Arrays::stream)
                    .collect(Collectors.toList())
            )
        );
    }

    public void retrieveRequests(ExpectationId expectationId, Consumer<List<RequestDefinition>> listConsumer) {
        retrieveLogEntries(
            expectationId != null ? Collections.singletonList(expectationId.getId()) : Collections.emptyList(),
            answeredRequestLogPredicate,
            logEntryToRequest,
            logEventStream -> listConsumer.accept(
                logEventStream
                    .filter(Objects::nonNull)
                    .flatMap(Arrays::stream)
                    .collect(Collectors.toList())
            )
        );
    }

    public void retrieveRequests(List<String> expectationIds, Consumer<List<RequestDefinition>> listConsumer) {
        retrieveLogEntries(
            expectationIds,
            answeredRequestLogPredicate,
            logEntryToRequest,
            logEventStream -> listConsumer.accept(
                logEventStream
                    .filter(Objects::nonNull)
                    .flatMap(Arrays::stream)
                    .collect(Collectors.toList())
            )
        );
    }

    public void retrieveRequestResponseMessageLogEntries(RequestDefinition requestDefinition, Consumer<List<LogEntry>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            requestResponseLogPredicate,
            (Stream<LogEntry> logEventStream) -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveRequestResponses(RequestDefinition requestDefinition, Consumer<List<LogEventRequestAndResponse>> listConsumer) {
        // Public retrieve/export surface (retrieveRecordedRequestsAndResponses) — redact when enabled.
        retrieveRequestResponses(requestDefinition, requestResponseLogPredicate, logEntryToRedactedHttpRequestAndHttpResponse, listConsumer);
    }

    private void retrieveRequestResponses(RequestDefinition requestDefinition, Predicate<LogEntry> logEntryPredicate, Function<LogEntry, LogEventRequestAndResponse> logEntryMapper, Consumer<List<LogEventRequestAndResponse>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            logEntryPredicate,
            logEntryMapper,
            logEventStream -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveRecordedExpectationLogEntries(RequestDefinition requestDefinition, Consumer<List<LogEntry>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            recordedExpectationLogPredicate,
            (Stream<LogEntry> logEventStream) -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveRecordedExpectations(RequestDefinition requestDefinition, Consumer<List<Expectation>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            recordedExpectationLogPredicate,
            logEntryToRecordedExpectation,
            logEventStream -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    /**
     * An empty filter runs no matcher, so it would also select DNS queries and binary messages; recorded requests
     * and verification counts read through it select only HTTP requests, as an empty HTTP matcher does.
     */
    private static Predicate<LogEntry> recordedRequestPredicate(RequestDefinition filter, Predicate<LogEntry> typePredicate) {
        return RequestMatchers.isUnfiltered(filter) ? typePredicate.and(notDnsOrBinaryRequest) : typePredicate;
    }

    /**
     * The matcher a log read filters by, or {@code null} (every entry) for no filter or the empty filter {@code {}}:
     * an empty HTTP matcher would drop the entries that carry a DNS or binary request.
     */
    private HttpRequestMatcher filterMatcher(RequestDefinition requestDefinition) {
        return RequestMatchers.isUnfiltered(requestDefinition) ? null : matcherBuilder.transformsToMatcher(requestDefinition);
    }

    private void retrieveLogEntries(RequestDefinition requestDefinition, Predicate<LogEntry> logEntryPredicate, Consumer<Stream<LogEntry>> consumer) {
        querySnapshot(false, snapshot -> {
            // build the matcher (which can throw, e.g. for an invalid OpenAPI/schema filter)
            // BEFORE invoking the consumer so any failure routes an empty stream to the consumer
            // rather than being swallowed by the disruptor exception handler — otherwise a verify
            // CompletableFuture waiting on the consumer would never complete and the caller hangs
            Stream<LogEntry> logEntryStream;
            try {
                HttpRequestMatcher httpRequestMatcher = filterMatcher(requestDefinition);
                logEntryStream = snapshot
                    .stream()
                    // cheap type/not-deleted predicate first so the expensive request matcher
                    // (which clones the request and runs full matching) only runs for entries
                    // that can actually be returned, not for deleted tombstones or wrong-type
                    // entries — keeps /retrieve cost low as the log fills (#2359)
                    .filter(logEntryPredicate)
                    .filter(logItem -> logItem.matches(httpRequestMatcher));
            } catch (Throwable throwable) {
                logger.error("exception building request matcher while retrieving log entries", throwable);
                logEntryStream = Stream.empty();
            }
            consumer.accept(logEntryStream);
        });
    }

    private <T> void retrieveLogEntries(RequestDefinition requestDefinition, Predicate<LogEntry> logEntryPredicate, Function<LogEntry, T> logEntryMapper, Consumer<Stream<T>> consumer) {
        querySnapshot(false, snapshot -> {
            // build the matcher before invoking the consumer so a build failure routes an empty
            // stream rather than being swallowed and hanging a waiting verify future — see above
            Stream<T> resultStream;
            try {
                HttpRequestMatcher httpRequestMatcher = filterMatcher(requestDefinition);
                resultStream = snapshot
                    .stream()
                    // cheap predicate before the expensive request matcher — see #2359
                    .filter(logEntryPredicate)
                    .filter(logItem -> logItem.matches(httpRequestMatcher))
                    .map(logEntryMapper);
            } catch (Throwable throwable) {
                logger.error("exception building request matcher while retrieving log entries", throwable);
                resultStream = Stream.empty();
            }
            consumer.accept(resultStream);
        });
    }

    @SuppressWarnings("SameParameterValue")
    private <T> void retrieveLogEntries(List<String> expectationIds, Predicate<LogEntry> logEntryPredicate, Function<LogEntry, T> logEntryMapper, Consumer<Stream<T>> consumer) {
        querySnapshot(false, snapshot -> consumer.accept(snapshot
            .stream()
            .filter(logEntryPredicate)
            .filter(logItem -> expectationIds == null || logItem.matchesAnyExpectationId(expectationIds))
            .map(logEntryMapper)
        ));
    }

    public void retrieveLogEntriesByCorrelationId(String correlationId, Consumer<List<LogEntry>> listConsumer) {
        querySnapshot(false, snapshot -> listConsumer.accept(snapshot
            .stream()
            .filter(notDeletedPredicate)
            .filter(logItem -> correlationId.equals(logItem.getCorrelationId()))
            .collect(Collectors.toList())
        ));
    }

    public void retrieveAlmostMatchedEntries(Consumer<List<LogEntry>> listConsumer) {
        querySnapshot(false, snapshot -> listConsumer.accept(snapshot
            .stream()
            .filter(notDeletedPredicate)
            .filter(logItem -> logItem.getType() == EXPECTATION_NOT_MATCHED)
            .filter(logItem -> {
                String msg = logItem.getMessageFormat();
                return msg != null && msg.startsWith("closest expectation:");
            })
            .collect(Collectors.toList())
        ));
    }

    /**
     * Retrieves the most recent NO_MATCH_RESPONSE log entries (requests that hit the server
     * and matched no expectation). Results are ordered most-recent-first and limited. A failed
     * forward's {@code 502} is logged with the same type but is left out: see {@link #retrieveForwardFailures}.
     *
     * @param limit       maximum number of entries to return (capped at 100)
     * @param listConsumer callback receiving the list of matching log entries
     */
    public void retrieveUnmatchedRequests(int limit, Consumer<List<LogEntry>> listConsumer) {
        drainDisruptor();
        final int effectiveLimit = Math.max(1, Math.min(limit, 100));
        querySnapshot(true, snapshot -> {
            List<LogEntry> entries = snapshot
                .stream()
                .filter(notDeletedPredicate)
                .filter(logItem -> logItem.getType() == NO_MATCH_RESPONSE && !isForwardFailure(logItem))
                .limit(effectiveLimit)
                .collect(Collectors.toList());
            listConsumer.accept(entries);
        });
    }

    /**
     * Retrieves the most recent failed forwards: the {@code failed to forward request} entries the forward action
     * writes with its {@code 502}, most recent first.
     *
     * @param correlationId only this request's entries, or {@code null} for any request
     * @param limit         maximum number of entries to return (capped at 100)
     * @param listConsumer  callback receiving the list of matching log entries
     */
    public void retrieveForwardFailures(String correlationId, int limit, Consumer<List<LogEntry>> listConsumer) {
        drainDisruptor();
        final int effectiveLimit = Math.max(1, Math.min(limit, 100));
        querySnapshot(true, snapshot -> listConsumer.accept(snapshot
            .stream()
            .filter(notDeletedPredicate)
            .filter(logItem -> logItem.getType() == NO_MATCH_RESPONSE && isForwardFailure(logItem))
            .filter(logItem -> correlationId == null || correlationId.equals(logItem.getCorrelationId()))
            .limit(effectiveLimit)
            .collect(Collectors.toList())
        ));
    }

    private static boolean isForwardFailure(LogEntry logItem) {
        return LogEntryMessages.FORWARD_FAILURE_MESSAGE_FORMAT.equals(logItem.getMessageFormat());
    }

    public <T> void retrieveLogEntriesInReverseForUI(RequestDefinition requestDefinition, Predicate<LogEntry> logEntryPredicate, Function<LogEntry, T> logEntryMapper, Consumer<Stream<T>> consumer) {
        // The live dashboard does NOT need the point-in-time snapshot that verify/retrieve depend on —
        // it only has to observe the entry that triggered this update, which processLogEntry guarantees
        // by adding to eventLog BEFORE notifyListeners fires. So skip querySnapshot, whose copy of the
        // ENTIRE retained log runs on the single disruptor consumer thread that ingests every request,
        // and traverse the deque's descendingIterator directly. ConcurrentLinkedDeque's iterators are
        // weakly consistent and safe to traverse while other threads append, and retained entries are
        // effectively immutable after cloneAndClear, so the scan reads stable state. It still runs off
        // the consumer thread so the per-entry match / mapping never blocks log ingestion.
        runOffConsumer(() -> {
            HttpRequestMatcher httpRequestMatcher = filterMatcher(requestDefinition);
            Stream<LogEntry> reverseStream = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(eventLog.descendingIterator(), Spliterator.ORDERED),
                false);
            consumer.accept(
                reverseStream
                    // cheap predicate before the expensive request matcher — see #2359
                    .filter(logEntryPredicate)
                    .filter(logItem -> logItem.matches(httpRequestMatcher))
                    .map(logEntryMapper)
            );
        });
    }

    /**
     * The retained, not deleted, entry with this {@link LogEntry#id()}, or {@code null} once it has been evicted
     * or cleared. Scans newest first off the consumer thread, as the dashboard's walk does.
     */
    public void retrieveLogEntryById(String id, Consumer<LogEntry> consumer) {
        runOffConsumer(() -> {
            LogEntry found = null;
            if (id != null) {
                Iterator<LogEntry> entries = eventLog.descendingIterator();
                while (entries.hasNext()) {
                    LogEntry entry = entries.next();
                    if (entry != null && !entry.isDeleted() && id.equals(entry.id())) {
                        found = entry;
                        break;
                    }
                }
            }
            consumer.accept(found);
        });
    }

    public Future<String> verify(Verification verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        verify(verification, result::complete);
        return result;
    }

    public void verify(Verification verification, Consumer<String> resultConsumer) {
        verify(verification, 0, resultConsumer);
    }

    /**
     * Evaluate a request-count verification, adding {@code additionalRemoteMatchCount} to this
     * node's local match count before comparing against the {@link org.mockserver.verify.VerificationTimes}.
     * <p>
     * Used by the T1.9 cluster verify fan-in: {@code HttpState} sums the match count reported by
     * every cluster peer's LOCAL log and passes the total remote count here, so a count-based
     * verification behind a load balancer is evaluated against the fleet-wide total rather than
     * only the traffic that reached this node. {@code additionalRemoteMatchCount} is {@code 0} on
     * the ordinary single-node / non-fan-in path (identical behaviour to before). Only applies to
     * request verification; response-aware verification always receives {@code 0}.
     */
    public void verify(Verification verification, int additionalRemoteMatchCount, Consumer<String> resultConsumer) {
        final long verificationStartedAtMillis = EpochService.currentTimeMillis();
        drainDisruptor();
        final String logCorrelationId = UUIDService.getNonSecureUUID();
        if (verification != null) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(VERIFICATION)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(logCorrelationId)
                        .setHttpRequest(verification.getHttpRequest())
                        .setMessageFormat(VERIFICATION_REQUESTS_MESSAGE_FORMAT)
                        .setArguments(verification)
                );
            }
            // A single evaluation of this verification against the current event log; it always calls
            // the supplied consumer exactly once with "" (pass) or a non-empty failure message. The
            // logResult flag controls whether this evaluation writes its VERIFICATION_PASSED/FAILED
            // outcome to the event log — suppressed for intermediate eventual-wait re-evaluations so a
            // failing-and-waiting verify does not flood the log with one VERIFICATION_FAILED per retry.
            final SingleVerificationEvaluation singleEvaluation = (logResult, consumer) -> {
                if (verification.getHttpResponse() != null) {
                    // response-aware verification: count recorded request-response pairs
                    // (cluster fan-in of response-aware verify is a deferred boundary — always local)
                    verifyResponse(verification, verificationStartedAtMillis, logCorrelationId, logResult, consumer);
                } else {
                    // original request-only verification (plus any cluster-peer remote match count)
                    verifyRequest(verification, additionalRemoteMatchCount, verificationStartedAtMillis, logCorrelationId, logResult, consumer);
                }
            };
            eventuallyVerify(verification.getTimeout(), singleEvaluation, resultConsumer);
        } else {
            resultConsumer.accept("");
        }
    }

    private void verifyRequest(Verification verification, int additionalRemoteMatchCount, long verificationStartedAtMillis, String logCorrelationId, boolean logResult, Consumer<String> resultConsumer) {
        retrieveRequests(verification, logCorrelationId, httpRequests -> {
            try {
                final int totalMatchedCount = httpRequests.size() + additionalRemoteMatchCount;
                if (!verification.getTimes().matches(totalMatchedCount)) {
                    final int matchedCount = totalMatchedCount;
                    boolean matchByExpectationId = verification.getExpectationId() != null;
                    countOtherDisposition(verification, logCorrelationId, logResult && !matchByExpectationId, otherDispositionCount -> retrieveAllRecordedRequests(matchByExpectationId, recordedRequests -> {
                        final List<RequestDefinition> allRequests = recordedRequests.stream().map(VerificationFailureSummary.RecordedRequest::request).collect(Collectors.toList());
                        String failureMessage;
                        String serializedRequestToBeVerified = requestDefinitionSerializer.serialize(true, verification.getHttpRequest());
                        Integer maximumNumberOfRequestToReturnInVerificationFailure = verification.getMaximumNumberOfRequestToReturnInVerificationFailure() != null ? verification.getMaximumNumberOfRequestToReturnInVerificationFailure() : configuration.maximumNumberOfRequestToReturnInVerificationFailure();
                        if (allRequests.size() < maximumNumberOfRequestToReturnInVerificationFailure) {
                            String serializedAllRequestInLog = allRequests.size() == 1 ? requestDefinitionSerializer.serialize(true, allRequests.get(0)) : requestDefinitionSerializer.serialize(true, allRequests);
                            failureMessage = countMismatch("Request", verification.getTimes(), matchedCount) + ", expected:<" + serializedRequestToBeVerified + "> but was:<" + serializedAllRequestInLog + ">";
                        } else {
                            failureMessage = countMismatch("Request", verification.getTimes(), matchedCount) + ", expected:<" + serializedRequestToBeVerified + "> but was found " + matchedCount + " time" + (matchedCount == 1 ? "" : "s") + " among " + allRequests.size() + " total requests";
                        }
                        if (configuration.detailedVerificationFailures() && !allRequests.isEmpty() && verification.getHttpRequest() instanceof HttpRequest) {
                            String diffSummary = buildClosestMatchDiff((HttpRequest) verification.getHttpRequest(), allRequests);
                            if (isNotBlank(diffSummary)) {
                                failureMessage += diffSummary;
                            }
                        }
                        // only the logged (final) evaluation's message reaches the caller; a waiting
                        // verify's in-between evaluations discard theirs, so skip the ranking there
                        if (logResult && configuration.detailedVerificationFailures() && verification.getHttpRequest() instanceof HttpRequest) {
                            failureMessage += buildVerificationSummary(verification, matchedCount, additionalRemoteMatchCount, otherDispositionCount, recordedRequests, verificationStartedAtMillis);
                        }
                        final Object received = allRequests.size() == 1 ? allRequests.get(0) : allRequests;
                        if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            LogEntry logEntry = new LogEntry()
                                .setType(VERIFICATION_FAILED)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(logCorrelationId)
                                .setHttpRequest(verification.getHttpRequest());
                            if (foundTooOften(verification.getTimes(), matchedCount)) {
                                logEntry
                                    .setMessageFormat("request found too often, it should have been found " + verification.getTimes() + ", expected:{}but was:{}")
                                    .setArguments(verification.getHttpRequest(), received);
                            } else {
                                logEntry
                                    .setMessageFormat("request not found " + verification.getTimes() + ", expected:{}but was:{}")
                                    .setArguments(verification.getHttpRequest(), received);
                            }
                            mockServerLogger.logEvent(logEntry);
                        }
                        resultConsumer.accept(failureMessage);
                    }));
                } else {
                    // The count satisfies the expected times — but a PASS that rests on an UPPER
                    // bound (never(), atMost(n), and the upper half of exactly/between) is only
                    // sound while the event log is a complete record. Once entries have been
                    // evicted, "found 0 times" may mean "the evidence was discarded", so passing
                    // here would be a silent false green — the single worst failure mode for a
                    // verification tool. Refuse to certify what we can no longer see.
                    UnprovableUpperBound evictionFailure = upperBoundUnprovableAfterEviction(verification, "Request");
                    if (evictionFailure != null) {
                        if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(VERIFICATION_FAILED)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(logCorrelationId)
                                    .setHttpRequest(verification.getHttpRequest())
                                    .setMessageFormat("request:{}could not be verified " + verification.getTimes() + " because the event log has " + (evictionFailure.dropped ? evictionFailure.evicted ? "dropped log events and evicted entries" : "dropped log events" : "evicted entries") + ":{}")
                                    .setArguments(verification.getHttpRequest(), evictionFailure.loss)
                            );
                        }
                        resultConsumer.accept(evictionFailure.message);
                        return;
                    }
                    if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(VERIFICATION_PASSED)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(logCorrelationId)
                                .setHttpRequest(verification.getHttpRequest())
                                .setMessageFormat("request:{}found " + verification.getTimes())
                                .setArguments(verification.getHttpRequest())
                        );
                    }
                    resultConsumer.accept("");
                }
            } catch (Throwable throwable) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(EXCEPTION)
                            .setCorrelationId(logCorrelationId)
                            .setMessageFormat("exception:{} while processing verification:{}")
                            .setArguments(throwable.getMessage(), verification)
                            .setThrowable(throwable)
                    );
                }
                resultConsumer.accept("exception while processing verification" + (isNotBlank(throwable.getMessage()) ? " " + throwable.getMessage() : ""));
            }
        });
    }

    /**
     * Decide whether a would-be PASS is actually unprovable because the event log has evicted
     * entries, returning the failure message (and the same counts and bounds as data, for the
     * {@code VERIFICATION_FAILED} log entry) when so and {@code null} when the PASS stands.
     * <p>
     * Only verifications carrying an <strong>upper</strong> bound are affected. The asymmetry is the
     * whole point: eviction can only ever make the observed count too LOW, so
     * <ul>
     *   <li>Affected (an upper bound is asserted, and the requests we did NOT see may simply have
     *       been discarded): {@code never()}, {@code atMost(n)}, {@code exactly(n)},
     *       {@code once()} and {@code between(a,b)}. Note {@code once()} is
     *       {@code VerificationTimes(1, 1)} — it asserts "exactly one", so eviction could be hiding
     *       a second call and it must fail closed like any other upper bound.</li>
     *   <li>Unaffected (no upper bound; we saw the requests and eviction cannot manufacture
     *       evidence): {@code atLeast(n)}, and therefore the bare {@code verify(request)} whose
     *       default is {@link org.mockserver.verify.VerificationTimes#atLeast(int) atLeast(1)}
     *       ({@code Verification.times}). These keep passing.</li>
     * </ul>
     * A FAIL is never converted to a pass here, so this can only ever make verification stricter.
     */
    private UnprovableUpperBound upperBoundUnprovableAfterEviction(Verification verification, String subject) {
        if (!configuration.failVerificationOnEvictedLog()) {
            return null;
        }
        int atMost = verification.getTimes() != null ? verification.getTimes().getAtMost() : -1;
        if (atMost == -1) {
            // no upper bound asserted — eviction cannot invalidate this pass
            return null;
        }
        // Ways the log stops being a complete record, each fatal to proving absence and each with its own
        // remedy: a recorded entry EVICTED from the retained deque (by the count or the byte bound), or
        // an incoming entry DROPPED before being recorded because the ring had no free slot or the
        // in-flight byte cap was reached. Drops are weight/type-agnostic, so the entry lost can be the
        // RECEIVED_REQUEST a verify reads. All four counts are since the last reset()/clear-all, and say
        // what the log lost, not that THIS verification's entries were among it.
        long evicted = eventLog.getEvictedCount();
        // read separately from the total, so clamp: an eviction between the two reads must not go negative
        long byteEvicted = Math.min(evicted, eventLog.getByteEvictedCount());
        long countEvicted = evicted - byteEvicted;
        long ringFull = ringFullDroppedSinceLogReset.get();
        long inFlightBytes = inFlightBytesDroppedSinceLogReset.get();
        if (evicted <= 0 && ringFull <= 0 && inFlightBytes <= 0) {
            return null;
        }
        StringBuilder message = new StringBuilder(subject)
            .append(" could not be verified ").append(verification.getTimes())
            .append(" because the event log is not a complete record: ");
        StringBuilder remedy = new StringBuilder("To fix: ");
        // The message's counts and bounds as data, for the log entry's argument. The dashboard reads these
        // keys (mockserver-ui/src/__fixtures__/incompleteLogVerificationFailure.json pins them on both sides).
        Map<String, Long> loss = new LinkedHashMap<>();
        if (ringFull > 0) {
            loss.put("droppedRingFull", ringFull);
            message
                .append(ringFull).append(ringFull == 1 ? " log event was" : " log events were")
                .append(" DROPPED before being recorded because the ring buffer was full (log events arrived faster than the single logging thread could record them). ");
            remedy.append("for the ring-full drops, lower the log level (e.g. to WARN) if they persist under steady load, or keep the log level and set maxLoggedMatchFailures (e.g. to 10) to cap the did-not-match entries logged per request — a larger ringBufferSize only absorbs short bursts; ");
        }
        if (inFlightBytes > 0) {
            long inFlightBudget = maxInFlightBytes;
            loss.put("droppedInFlightBytes", inFlightBytes);
            loss.put("inFlightBytesBudget", inFlightBudget);
            message
                .append(inFlightBytes).append(inFlightBytes == 1 ? " log event was" : " log events were")
                .append(" DROPPED before being recorded because the request/response bodies waiting to be logged exceeded the in-flight byte budget of ")
                .append(inFlightBudget).append(" bytes (the larger of maxEventLogSizeInBytes and a heap-derived cap). ");
            remedy.append("for the in-flight byte drops, lower the log level or, if you have heap to spare, raise maxEventLogSizeInBytes above that budget (maxLoggedBodyBytes does not help: it truncates bodies only after they leave this backlog); ");
        }
        if (countEvicted > 0) {
            long maxLogEntries = configuration.maxLogEntries();
            loss.put("evictedAtMaxLogEntries", countEvicted);
            loss.put("maxLogEntries", maxLogEntries);
            message
                .append(countEvicted).append(countEvicted == 1 ? " recorded entry was" : " recorded entries were")
                .append(" EVICTED after the log reached its maximum number of entries (maxLogEntries=").append(maxLogEntries).append("). ");
            remedy.append("for the evictions at maxLogEntries, raise it, or lower the log level so fewer entries are recorded per request, or keep the log level and set maxLoggedMatchFailures (e.g. to 10) to cap the did-not-match entries logged per request; ");
        }
        if (byteEvicted > 0) {
            long maxEventLogSizeInBytes = configuration.maxEventLogSizeInBytes();
            loss.put("evictedAtMaxEventLogSizeInBytes", byteEvicted);
            loss.put("maxEventLogSizeInBytes", maxEventLogSizeInBytes);
            message
                .append(byteEvicted).append(byteEvicted == 1 ? " recorded entry was" : " recorded entries were")
                .append(" EVICTED after the log reached its maximum size in bytes (maxEventLogSizeInBytes=").append(maxEventLogSizeInBytes).append("). ");
            remedy.append("for the evictions at maxEventLogSizeInBytes, raise it, or set maxLoggedBodyBytes to truncate large bodies so each recorded entry is smaller; ");
        }
        return new UnprovableUpperBound(ringFull > 0 || inFlightBytes > 0, evicted > 0, Collections.unmodifiableMap(loss), message
            .append("Absence cannot be proven — the matching requests may have been discarded rather than never made. ")
            .append(remedy)
            .append("or reset the event log between tests, or set failVerificationOnEvictedLog=false to restore the previous (unsound) behaviour.")
            .toString());
    }

    /** Why an upper-bound PASS cannot stand: what the log lost (for the log entry), and the full failure message. */
    private static final class UnprovableUpperBound {
        private final boolean dropped;
        private final boolean evicted;
        private final Map<String, Long> loss;
        private final String message;

        private UnprovableUpperBound(boolean dropped, boolean evicted, Map<String, Long> loss, String message) {
            this.dropped = dropped;
            this.evicted = evicted;
            this.loss = loss;
            this.message = message;
        }
    }

    /**
     * Lead phrase of a count-verification failure. "not found" is only true when too FEW matched;
     * when an upper bound was exceeded the subject WAS found, too often, so say that instead.
     */
    static String countMismatch(String subject, VerificationTimes times, int matchedCount) {
        if (foundTooOften(times, matchedCount)) {
            return subject + " found " + matchedCount + " time" + (matchedCount == 1 ? "" : "s") + " but should have been found " + times;
        }
        return subject + " not found " + times;
    }

    static boolean foundTooOften(VerificationTimes times, int matchedCount) {
        return times.getAtMost() != -1 && matchedCount > times.getAtMost();
    }

    private void verifyResponse(Verification verification, long verificationStartedAtMillis, String logCorrelationId, boolean logResult, Consumer<String> resultConsumer) {
        RequestDefinition requestFilter = verification.getHttpRequest() != null
            ? verification.getHttpRequest().withLogCorrelationId(logCorrelationId)
            : null;
        retrieveRecordedExchanges(requestFilter, recordedExchanges -> {
            try {
                final List<LogEventRequestAndResponse> allPairs = recordedExchanges.stream().map(VerificationFailureSummary.RecordedExchange::exchange).collect(Collectors.toList());
                HttpResponseMatcher responseMatcher = new HttpResponseMatcher(configuration, mockServerLogger, verification.getHttpResponse());
                List<LogEventRequestAndResponse> matchingPairs = allPairs.stream()
                    .filter(pair -> responseMatcher.matches(pair.getHttpResponse()))
                    .collect(Collectors.toList());
                int matchedCount = matchingPairs.size();
                if (!verification.getTimes().matches(matchedCount)) {
                    // matched above on the raw pairs; the returned failure message is built from redacted copies,
                    // while the logged entry keeps the raw pairs (redacted when it is rendered, not retained twice)
                    List<LogEventRequestAndResponse> recordedPairs = redactedForDisplay(allPairs);
                    HttpResponseSerializer httpResponseSerializer = new HttpResponseSerializer(mockServerLogger);
                    String serializedResponseToBeVerified = httpResponseSerializer.serialize(verification.getHttpResponse());
                    Integer maximumNumberOfRequestToReturnInVerificationFailure = verification.getMaximumNumberOfRequestToReturnInVerificationFailure() != null ? verification.getMaximumNumberOfRequestToReturnInVerificationFailure() : configuration.maximumNumberOfRequestToReturnInVerificationFailure();
                    String failureMessage;
                    if (recordedPairs.size() < maximumNumberOfRequestToReturnInVerificationFailure) {
                        List<HttpResponse> allResponses = recordedPairs.stream()
                            .map(LogEventRequestAndResponse::getHttpResponse)
                            .collect(Collectors.toList());
                        String serializedAllResponsesInLog = allResponses.size() == 1
                            ? httpResponseSerializer.serialize(allResponses.get(0))
                            : httpResponseSerializer.serialize(allResponses);
                        failureMessage = countMismatch("Response", verification.getTimes(), matchedCount) + ", expected:<" + serializedResponseToBeVerified + "> but was:<" + serializedAllResponsesInLog + ">";
                    } else {
                        failureMessage = countMismatch("Response", verification.getTimes(), matchedCount) + ", expected:<" + serializedResponseToBeVerified + "> but was found " + matchedCount + " time" + (matchedCount == 1 ? "" : "s") + " among " + allPairs.size() + " recorded responses";
                    }
                    // Mirror the request side (buildClosestMatchDiff): when detailed failures are on and
                    // there is at least one recorded response to compare against, append a field-level
                    // "closest response" diff to the failure message. This is diagnostic only — it never
                    // changes the pass/fail result (already inside the failed branch) and is gated
                    // identically to the request-side closest-match diff (which does NOT gate on INFO, so
                    // the diff reaches the returned failure message regardless of log level).
                    if (configuration.detailedVerificationFailures() && !recordedPairs.isEmpty()) {
                        List<HttpResponse> recordedResponses = recordedPairs.stream()
                            .map(LogEventRequestAndResponse::getHttpResponse)
                            .filter(Objects::nonNull)
                            // bound the work like the request side — a huge recorded log should not blow
                            // up the diff computation; cap at the same configured maximum
                            .limit(maximumNumberOfRequestToReturnInVerificationFailure)
                            .collect(Collectors.toList());
                        String diffSummary = buildClosestResponseMatchDiff(verification, verification.getHttpResponse(), recordedResponses);
                        if (isNotBlank(diffSummary)) {
                            failureMessage += diffSummary;
                        }
                    }
                    // as on the request side, only the logged (final) evaluation's message reaches the caller
                    if (logResult && configuration.detailedVerificationFailures()) {
                        final boolean waited = verification.getTimeout() != null && verification.getTimeout() > 0;
                        failureMessage += safeSummary(() -> VerificationFailureSummary.buildForResponses(responseMatcher, verification.getTimes(), matchedCount, recordedExchanges, recordedPairs, verificationStartedAtMillis, waited));
                    }
                    if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        LogEntry logEntry = new LogEntry()
                            .setType(VERIFICATION_FAILED)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(logCorrelationId)
                            .setHttpRequest(verification.getHttpRequest());
                        if (foundTooOften(verification.getTimes(), matchedCount)) {
                            logEntry
                                .setMessageFormat("response found too often, it should have been found " + verification.getTimes() + ", expected:{}but was:{}")
                                .setArguments(verification.getHttpResponse(), allPairs);
                        } else {
                            logEntry
                                .setMessageFormat("response not found " + verification.getTimes() + ", expected:{}but was:{}")
                                .setArguments(verification.getHttpResponse(), allPairs);
                        }
                        mockServerLogger.logEvent(logEntry);
                    }
                    resultConsumer.accept(failureMessage);
                } else {
                    // Same soundness rule as the request side: an upper-bound PASS is not provable
                    // once the log has evicted, because the responses we did not find may simply
                    // have been discarded. See upperBoundUnprovableAfterEviction.
                    UnprovableUpperBound evictionFailure = upperBoundUnprovableAfterEviction(verification, "Response");
                    if (evictionFailure != null) {
                        if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(VERIFICATION_FAILED)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(logCorrelationId)
                                    .setHttpRequest(verification.getHttpRequest())
                                    .setMessageFormat("response:{}could not be verified " + verification.getTimes() + " because the event log has " + (evictionFailure.dropped ? evictionFailure.evicted ? "dropped log events and evicted entries" : "dropped log events" : "evicted entries") + ":{}")
                                    .setArguments(verification.getHttpResponse(), evictionFailure.loss)
                            );
                        }
                        resultConsumer.accept(evictionFailure.message);
                        return;
                    }
                    if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(VERIFICATION_PASSED)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(logCorrelationId)
                                .setHttpRequest(verification.getHttpRequest())
                                .setMessageFormat("response:{}found " + verification.getTimes())
                                .setArguments(verification.getHttpResponse())
                        );
                    }
                    resultConsumer.accept("");
                }
            } catch (Throwable throwable) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(EXCEPTION)
                            .setCorrelationId(logCorrelationId)
                            .setMessageFormat("exception:{} while processing verification:{}")
                            .setArguments(throwable.getMessage(), verification)
                            .setThrowable(throwable)
                    );
                }
                resultConsumer.accept("exception while processing verification" + (isNotBlank(throwable.getMessage()) ? " " + throwable.getMessage() : ""));
            }
        });
    }

    /**
     * Runs one verification evaluation against the current event log, invoking its consumer once with
     * "" (pass) or a non-empty failure message. The {@code logResult} flag decides whether this
     * evaluation writes its {@code VERIFICATION_PASSED}/{@code VERIFICATION_FAILED} outcome to the event
     * log — set {@code false} for intermediate eventual-wait re-evaluations so they don't pollute the log.
     */
    @FunctionalInterface
    private interface SingleVerificationEvaluation {
        void evaluate(boolean logResult, Consumer<String> resultConsumer);
    }

    /**
     * Server-side eventual-verification harness shared by request, response and sequence verification.
     *
     * <p>When {@code timeoutMillis} is {@code null} or {@code <= 0} this runs the supplied
     * single-evaluation exactly once <em>with logging on</em> and forwards its result verbatim —
     * byte-identical to the original single-shot behaviour (no listener, no scheduling, one
     * {@code VERIFICATION_PASSED}/{@code VERIFICATION_FAILED} log entry exactly as before).</p>
     *
     * <p>When {@code timeoutMillis > 0} (clamped to {@link #MAX_VERIFY_TIMEOUT_MILLIS}): it runs the
     * single evaluation with logging <em>suppressed</em>; if it PASSES it completes immediately. If it
     * FAILS it registers a transient {@link MockServerLogListener} on this event log and arms a deadline
     * on the scheduler's executor. On each coalesced {@code updated(...)} notification it re-runs the
     * evaluation (still logging-suppressed) until it passes, and the deadline fires otherwise. The
     * winning path — first passing re-evaluation OR the deadline — runs one FINAL evaluation with
     * logging ON so exactly one outcome (passed or failed) is logged for the whole wait, instead of one
     * {@code VERIFICATION_FAILED} per retry. A single {@link AtomicBoolean} completion guard ensures the
     * result consumer is invoked exactly once whichever fires first, and the transient listener and
     * deadline future are always cleaned up so neither leaks. The wait never blocks a Netty I/O thread —
     * re-evaluation and deadline both run on the scheduler, completion is delivered through the async
     * result consumer.</p>
     *
     * <p>If no scheduled executor is available (synchronous {@code Scheduler}, e.g. WAR/servlet) the
     * eventual path cannot be armed, so it degrades to the logging-on single-shot result.</p>
     */
    private void eventuallyVerify(Long timeoutMillis, SingleVerificationEvaluation singleEvaluation, Consumer<String> resultConsumer) {
        final long effectiveTimeout = timeoutMillis == null ? 0L : Math.min(timeoutMillis, MAX_VERIFY_TIMEOUT_MILLIS);
        final ScheduledExecutorService executor = getScheduler().getExecutorService();
        if (effectiveTimeout <= 0 || executor == null || executor.isShutdown()) {
            // single-shot: original behaviour, logging on, no listener and no scheduling
            singleEvaluation.evaluate(true, resultConsumer);
            return;
        }
        // first evaluation with logging suppressed: complete immediately on pass (logging the single
        // PASSED outcome via a final logging-on evaluation), only arm the eventual path on failure
        singleEvaluation.evaluate(false, firstFailureMessage -> {
            if (isBlank(firstFailureMessage)) {
                // re-evaluate once with logging on to emit the single PASSED outcome and complete
                singleEvaluation.evaluate(true, resultConsumer);
            } else {
                armEventualVerification(effectiveTimeout, executor, singleEvaluation, resultConsumer);
            }
        });
    }

    private void armEventualVerification(long effectiveTimeout, ScheduledExecutorService executor, SingleVerificationEvaluation singleEvaluation, Consumer<String> resultConsumer) {
        final AtomicBoolean completed = new AtomicBoolean(false);
        // holder so the listener can unregister itself and the deadline can be cancelled on completion
        final MockServerLogListener[] listenerHolder = new MockServerLogListener[1];
        final ScheduledFuture<?>[] deadlineHolder = new ScheduledFuture<?>[1];

        final Runnable cleanup = () -> {
            if (listenerHolder[0] != null) {
                unregisterListener(listenerHolder[0]);
            }
            if (deadlineHolder[0] != null) {
                deadlineHolder[0].cancel(false);
            }
        };
        // Claims completion exactly once, then runs ONE final logging-on evaluation so the single
        // PASSED/FAILED outcome is logged and the real result (re-derived now, including any request
        // that arrived right at the deadline) is forwarded to the caller. Cleanup happens first so the
        // listener/deadline are gone before the final evaluation runs.
        final Runnable completeOnce = () -> {
            if (completed.compareAndSet(false, true)) {
                cleanup.run();
                singleEvaluation.evaluate(true, resultConsumer);
            }
        };

        final MockServerLogListener listener = mockServerLog -> {
            if (completed.get()) {
                return;
            }
            // re-run the evaluation (logging suppressed) on each coalesced log change; only a pass
            // triggers completion — a continued failure just keeps waiting for the deadline
            singleEvaluation.evaluate(false, message -> {
                if (isBlank(message)) {
                    completeOnce.run();
                }
            });
        };
        listenerHolder[0] = listener;
        registerListener(listener);

        deadlineHolder[0] = Scheduler.scheduleUnlessShutDown(executor, completeOnce, effectiveTimeout, TimeUnit.MILLISECONDS);
        if (deadlineHolder[0] == null) {
            // executor shut down between the isShutdown() check and scheduling — complete now so the
            // caller never hangs, and clean up the just-registered listener
            completeOnce.run();
            return;
        }
        // If a notification already completed the verification while the deadline was being scheduled,
        // cleanup ran before deadlineHolder was assigned — cancel the now-assigned future so it never
        // fires (it would be a harmless no-op via the guard, but cancelling avoids a lingering timer).
        if (completed.get()) {
            deadlineHolder[0].cancel(false);
        }

        // A passing event may have arrived between the first evaluation and the listener registration.
        // Re-run once more now so we don't miss it while waiting for the next (coalesced) notification.
        listener.updated(this);
    }

    public Future<String> verify(VerificationSequence verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        verify(verification, result::complete);
        return result;
    }

    public void verify(VerificationSequence verificationSequence, Consumer<String> resultConsumer) {
        final long verificationStartedAtMillis = EpochService.currentTimeMillis();
        drainDisruptor();
        if (verificationSequence != null) {
            final String logCorrelationId = UUIDService.getNonSecureUUID();
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(VERIFICATION)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(logCorrelationId)
                        .setHttpRequests(verificationSequence.getHttpRequests().toArray(new RequestDefinition[0]))
                        .setMessageFormat(VERIFICATION_REQUEST_SEQUENCES_MESSAGE_FORMAT)
                        .setArguments(verificationSequence)
                );
            }
            eventuallyVerify(
                verificationSequence.getTimeout(),
                (logResult, consumer) -> verifySequenceOnce(verificationSequence, verificationStartedAtMillis, logCorrelationId, logResult, consumer),
                resultConsumer
            );
        } else {
            resultConsumer.accept("");
        }
    }

    private void verifySequenceOnce(VerificationSequence verificationSequence, long verificationStartedAtMillis, String logCorrelationId, boolean logResult, Consumer<String> resultConsumer) {
        {
            // only the logged (final) evaluation's message reaches the caller, so only it builds a summary
            final boolean summarise = logResult && configuration.detailedVerificationFailures();
            final boolean waited = verificationSequence.getTimeout() != null && verificationSequence.getTimeout() > 0;
            boolean hasExpectationIds = verificationSequence.getExpectationIds() != null && !verificationSequence.getExpectationIds().isEmpty();
            boolean hasRequests = verificationSequence.getHttpRequests() != null && !verificationSequence.getHttpRequests().isEmpty();
            boolean hasResponses = verificationSequence.getHttpResponses() != null && !verificationSequence.getHttpResponses().isEmpty();
            if (!hasExpectationIds && !hasRequests && !hasResponses) {
                // an entirely-empty sequence (no expectationIds, no requests, no responses) is
                // meaningless — reject it with a clear error rather than vacuously passing
                resultConsumer.accept("No expectations, requests or responses specified in verification sequence");
                return;
            }
            if (hasExpectationIds) {
                retrieveAllRequests(verificationSequence.getExpectationIds().stream().map(ExpectationId::getId).collect(Collectors.toList()), allRequests -> {
                    List<RequestDefinition> requestDefinitions = allRequests.stream().map(RequestAndExpectationId::getRequestDefinition).collect(Collectors.toList());
                    try {
                        String failureMessage = "";
                        int requestLogCounter = 0;
                        int previousStepNumber = 0;
                        List<ExpectationId> expectationIds = verificationSequence.getExpectationIds();
                        for (int step = 0; step < expectationIds.size(); step++) {
                            ExpectationId expectationId = expectationIds.get(step);
                            if (expectationId != null) {
                                final int searchedFrom = requestLogCounter;
                                boolean foundRequest = false;
                                for (; !foundRequest && requestLogCounter < allRequests.size(); requestLogCounter++) {
                                    if (allRequests.get(requestLogCounter).matches(expectationId)) {
                                        // move on to next request
                                        foundRequest = true;
                                    }
                                }
                                if (!foundRequest) {
                                    // expectation-ID sequence steps match by recorded expectation id, not by
                                    // request fields, so there is no per-field template to diff against — pass
                                    // null so no closest-match diff is appended for this path
                                    failureMessage = verificationSequenceFailureMessage(verificationSequence, logCorrelationId, logResult, requestDefinitions, null);
                                    if (summarise) {
                                        final int stepNumber = step + 1;
                                        final int stepPreviousNumber = previousStepNumber;
                                        failureMessage += safeSummary(() -> VerificationFailureSummary.buildForExpectationIdSequence(
                                            stepNumber, expectationIds.size(), stepPreviousNumber, allRequests.size(), searchedFrom,
                                            index -> allRequests.get(index).matches(expectationId)));
                                    }
                                    break;
                                }
                                previousStepNumber = step + 1;
                            }
                        }
                        verificationSequenceSuccessMessage(verificationSequence, resultConsumer, logCorrelationId, logResult, failureMessage);

                    } catch (Throwable throwable) {
                        verificationSequenceExceptionHandler(verificationSequence, resultConsumer, logCorrelationId, throwable, "exception:{} while processing verification sequence:{}", "exception while processing verification sequence");
                    }
                });
            } else if (hasResponses) {
                // response-aware sequence verification over recorded request-response pairs.
                // Only count responses the mocks actually produced (EXPECTATION_RESPONSE +
                // FORWARDED_REQUEST), never MockServer's own NO_MATCH_RESPONSE auto-404s.
                final List<RequestDefinition> httpRequests = verificationSequence.getHttpRequests();
                final List<HttpResponse> httpResponses = verificationSequence.getHttpResponses();
                // A response-aware sequence pairs the i-th request with the i-th response, so when
                // both lists are supplied they MUST be the same length. Previously the shorter list
                // was padded with null and a null matcher always matched, so a mismatched-length
                // sequence silently passed on the unspecified steps — reject it instead. One list
                // being empty (request-only or response-only sequence) is still valid.
                if (!httpRequests.isEmpty() && httpRequests.size() != httpResponses.size()) {
                    resultConsumer.accept(
                        "Request and response sequences must be the same length for a response-aware verification sequence, found "
                            + httpRequests.size() + " request(s) and " + httpResponses.size() + " response(s)"
                    );
                    return;
                }
                retrieveRecordedExchanges(null, recordedExchanges -> {
                    try {
                        final List<LogEventRequestAndResponse> allPairs = recordedExchanges.stream().map(VerificationFailureSummary.RecordedExchange::exchange).collect(Collectors.toList());
                        String failureMessage = "";
                        int pairLogCounter = 0;
                        int previousStepNumber = 0;
                        // requests are either absent (response-only) or the same length as responses
                        // (enforced above), so the response count is the number of steps to verify
                        int stepCount = httpResponses.size();
                        for (int i = 0; i < stepCount; i++) {
                            RequestDefinition verificationHttpRequest = i < httpRequests.size() ? httpRequests.get(i) : null;
                            HttpResponse verificationHttpResponse = i < httpResponses.size() ? httpResponses.get(i) : null;
                            HttpRequestMatcher httpRequestMatcher = verificationHttpRequest != null
                                ? matcherBuilder.transformsToMatcher(verificationHttpRequest.withLogCorrelationId(logCorrelationId))
                                : null;
                            HttpResponseMatcher httpResponseMatcher = verificationHttpResponse != null
                                ? new HttpResponseMatcher(configuration, mockServerLogger, verificationHttpResponse)
                                : null;
                            final int searchedFrom = pairLogCounter;
                            boolean foundMatch = false;
                            for (; !foundMatch && pairLogCounter < allPairs.size(); pairLogCounter++) {
                                LogEventRequestAndResponse pair = allPairs.get(pairLogCounter);
                                // a pair with a null recorded request can never satisfy a
                                // request-constrained step — treat it as non-matching rather than
                                // dereferencing it (which would NPE and be masked as a generic
                                // "exception while processing verification sequence")
                                boolean requestMatches = httpRequestMatcher == null
                                    || (pair.getHttpRequest() != null && httpRequestMatcher.matches(((HttpRequest) pair.getHttpRequest()).cloneWithLogCorrelationId()));
                                boolean responseMatches = httpResponseMatcher == null || httpResponseMatcher.matches(pair.getHttpResponse());
                                if (requestMatches && responseMatches) {
                                    foundMatch = true;
                                }
                            }
                            if (!foundMatch) {
                                final List<LogEventRequestAndResponse> displayPairs = redactedForDisplay(allPairs);
                                List<HttpResponse> recordedResponses = displayPairs.stream()
                                    .map(LogEventRequestAndResponse::getHttpResponse)
                                    .collect(Collectors.toList());
                                List<HttpResponse> rawRecordedResponses = allPairs.stream()
                                    .map(LogEventRequestAndResponse::getHttpResponse)
                                    .collect(Collectors.toList());
                                failureMessage = verificationResponseSequenceFailureMessage(verificationSequence, logCorrelationId, logResult, recordedResponses, rawRecordedResponses, verificationHttpRequest, verificationHttpResponse);
                                if (summarise) {
                                    final int stepNumber = i + 1;
                                    final int stepPreviousNumber = previousStepNumber;
                                    // as for a request sequence, a non-HttpRequest (OpenAPI) step gets the step line only
                                    final boolean rankable = verificationHttpRequest == null || verificationHttpRequest instanceof HttpRequest;
                                    final HttpRequestMatcher stepRequestMatcher = rankable ? httpRequestMatcher : null;
                                    failureMessage += safeSummary(() -> VerificationFailureSummary.buildForResponseSequence(
                                        stepNumber, stepCount, stepPreviousNumber, rankable, stepRequestMatcher, httpResponseMatcher, recordedExchanges, displayPairs, searchedFrom, verificationStartedAtMillis, waited));
                                }
                                break;
                            }
                            previousStepNumber = i + 1;
                        }
                        verificationSequenceSuccessMessage(verificationSequence, resultConsumer, logCorrelationId, logResult, failureMessage);
                    } catch (Throwable throwable) {
                        verificationSequenceExceptionHandler(verificationSequence, resultConsumer, logCorrelationId, throwable, "exception:{} while processing verification sequence:{}", "exception while processing verification sequence");
                    }
                });
            } else {
                final BiConsumer<List<RequestDefinition>, List<VerificationFailureSummary.RecordedRequest>> evaluateSequence = (allRequests, recordedRequests) -> {
                    try {
                        String failureMessage = "";
                        int requestLogCounter = 0;
                        int previousStepNumber = 0;
                        List<RequestDefinition> stepRequests = verificationSequence.getHttpRequests();
                        for (int step = 0; step < stepRequests.size(); step++) {
                            RequestDefinition verificationHttpRequest = stepRequests.get(step);
                            if (verificationHttpRequest != null) {
                                verificationHttpRequest.withLogCorrelationId(logCorrelationId);
                                HttpRequestMatcher httpRequestMatcher = matcherBuilder.transformsToMatcher(verificationHttpRequest);
                                final int searchedFrom = requestLogCounter;
                                boolean foundRequest = false;
                                for (; !foundRequest && requestLogCounter < allRequests.size(); requestLogCounter++) {
                                    if (httpRequestMatcher.matches(allRequests.get(requestLogCounter).cloneWithLogCorrelationId())) {
                                        // move on to next request
                                        foundRequest = true;
                                    }
                                }
                                if (!foundRequest) {
                                    failureMessage = verificationSequenceFailureMessage(verificationSequence, logCorrelationId, logResult, allRequests, verificationHttpRequest);
                                    if (summarise) {
                                        final int stepNumber = step + 1;
                                        final int stepPreviousNumber = previousStepNumber;
                                        final HttpRequestMatcher stepMatcher = verificationHttpRequest instanceof HttpRequest ? httpRequestMatcher : null;
                                        failureMessage += safeSummary(() -> VerificationFailureSummary.buildForRequestSequence(
                                            stepNumber, stepRequests.size(), stepPreviousNumber, stepMatcher, recordedRequests, searchedFrom, verificationStartedAtMillis, waited));
                                    }
                                    break;
                                }
                                previousStepNumber = step + 1;
                            }
                        }
                        verificationSequenceSuccessMessage(verificationSequence, resultConsumer, logCorrelationId, logResult, failureMessage);

                    } catch (Throwable throwable) {
                        verificationSequenceExceptionHandler(verificationSequence, resultConsumer, logCorrelationId, throwable, "exception:{} while processing verification sequence:{}", "exception while processing verification sequence");
                    }
                };
                if (summarise) {
                    retrieveAllRecordedRequests(false, LogEntry::getHttpRequests, recordedRequests -> evaluateSequence.accept(recordedRequests.stream().map(VerificationFailureSummary.RecordedRequest::request).collect(Collectors.toList()), recordedRequests));
                } else {
                    // only the summary reads arrival times, so the other evaluations skip the wrappers
                    retrieveLogEntries(
                        (RequestDefinition) null,
                        requestLogPredicate,
                        LogEntry::getHttpRequests,
                        logEventStream -> evaluateSequence.accept(logEventStream.filter(Objects::nonNull).flatMap(Arrays::stream).collect(Collectors.toList()), null)
                    );
                }
            }
        }
    }

    private List<RequestDefinition> redactedRequestsForDisplay(List<RequestDefinition> requests) {
        org.mockserver.fixture.FixtureRedactor redactor = LogEntry.eventLogRedactor(configuration);
        if (redactor == null) {
            return requests;
        }
        return requests.stream().map(request -> request == null ? null : redactor.redactRequestDefinition(request)).collect(Collectors.toList());
    }

    /**
     * Recorded pairs as a verification failure may show them: redacted copies when {@code redactSecretsInLog}
     * is on, as the request side shows requests; the same list, unchanged, when it is off.
     */
    private List<LogEventRequestAndResponse> redactedForDisplay(List<LogEventRequestAndResponse> pairs) {
        org.mockserver.fixture.FixtureRedactor redactor = LogEntry.eventLogRedactor(configuration);
        if (redactor == null) {
            return pairs;
        }
        return pairs.stream()
            .map(pair -> new LogEventRequestAndResponse()
                .withTimestamp(pair.getTimestamp())
                .withHttpRequest(pair.getHttpRequest() == null ? null : (HttpRequest) redactor.redactRequestDefinition(pair.getHttpRequest()))
                .withHttpResponse(redactor.redactResponseObject(pair.getHttpResponse())))
            .collect(Collectors.toList());
    }

    private String buildClosestMatchDiff(HttpRequest verificationRequest, List<RequestDefinition> allRequests) {
        try {
            HttpRequestMatcher verificationMatcher = matcherBuilder.transformsToMatcher(verificationRequest);
            int closestMatchFailures = Integer.MAX_VALUE;
            Map<MatchDifference.Field, List<String>> closestDifferences = null;
            int totalFields = MatchDifference.Field.values().length;

            for (RequestDefinition receivedRequest : allRequests) {
                if (receivedRequest instanceof HttpRequest) {
                    HttpRequest received = (HttpRequest) receivedRequest;
                    MatchDifference matchDifference = new MatchDifference(true, received);
                    verificationMatcher.matches(matchDifference, received);
                    Map<MatchDifference.Field, List<String>> differences = matchDifference.getAllDifferences();
                    int failures = differences.size();
                    if (failures < closestMatchFailures) {
                        closestMatchFailures = failures;
                        closestDifferences = differences;
                        if (failures == 0) {
                            break;
                        }
                    }
                }
            }

            if (closestDifferences != null && !closestDifferences.isEmpty()) {
                return MatchDifferenceFormatter.formatDifferences(closestDifferences);
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("exception generating closest match diff:{}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
            }
        }
        return "";
    }

    private String buildVerificationSummary(Verification verification, int matchedCount, int remoteMatchedCount, int otherDispositionCount, List<VerificationFailureSummary.RecordedRequest> recordedRequests, long verificationStartedAtMillis) {
        return safeSummary(() -> {
            HttpRequestMatcher verificationMatcher = matcherBuilder.transformsToMatcher(verification.getHttpRequest());
            boolean waited = verification.getTimeout() != null && verification.getTimeout() > 0;
            return VerificationFailureSummary.build(verificationMatcher, verification.getTimes(), matchedCount, remoteMatchedCount, verification.getDisposition(), otherDispositionCount, recordedRequests, verificationStartedAtMillis, waited);
        });
    }

    /**
     * The summary is diagnostic only: a failure building it must never change or lose the verification result.
     */
    private String safeSummary(Supplier<String> summary) {
        try {
            return summary.get();
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("exception generating verification summary:{}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
            }
            return "";
        }
    }

    /**
     * Response-verification analogue of {@link #buildClosestMatchDiff(HttpRequest, List)}: compares the
     * verification response template against each recorded response and returns a field-level diff for
     * the closest one (fewest differing fields), formatted by {@link MatchDifferenceFormatter} exactly
     * as the request side is. Diagnostic only — the result is appended to the failure message and never
     * affects the pass/fail outcome.
     * <p>
     * The {@code verification} argument supplies a non-null request for the {@link MatchDifference}: it
     * dereferences its {@code httpRequest} when TRACE logging records a difference, so a null request
     * would NPE — fall back to an empty {@link HttpRequest#request()} placeholder when the verification
     * carries no request (a response-only verify), mirroring the request side's non-null guarantee.
     */
    private String buildClosestResponseMatchDiff(Verification verification, HttpResponse verificationResponse, List<HttpResponse> recordedResponses) {
        try {
            HttpResponseMatcher verificationMatcher = new HttpResponseMatcher(configuration, mockServerLogger, verificationResponse);
            // MatchDifference dereferences its request under TRACE logging, so it must never be null —
            // use the verification request when present, otherwise a non-null empty request placeholder
            RequestDefinition diffRequest = verification.getHttpRequest() instanceof HttpRequest
                ? (HttpRequest) verification.getHttpRequest()
                : request();
            int closestMatchFailures = Integer.MAX_VALUE;
            Map<MatchDifference.Field, List<String>> closestDifferences = null;

            for (HttpResponse recordedResponse : recordedResponses) {
                // defensive: the call site already filters nulls, but guard here too so this helper is
                // safe in isolation (HttpResponseMatcher.matches returns false on a null actual anyway)
                if (recordedResponse == null) {
                    continue;
                }
                MatchDifference matchDifference = new MatchDifference(true, diffRequest);
                verificationMatcher.matches(matchDifference, recordedResponse);
                Map<MatchDifference.Field, List<String>> differences = matchDifference.getAllDifferences();
                int failures = differences.size();
                if (failures < closestMatchFailures) {
                    closestMatchFailures = failures;
                    closestDifferences = differences;
                    if (failures == 0) {
                        break;
                    }
                }
            }

            if (closestDifferences != null && !closestDifferences.isEmpty()) {
                return MatchDifferenceFormatter.formatDifferences(closestDifferences);
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("exception generating closest response match diff:{}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
            }
        }
        return "";
    }

    private void verificationSequenceSuccessMessage(VerificationSequence verificationSequence, Consumer<String> resultConsumer, String logCorrelationId, boolean logResult, String failureMessage) {
        if (isBlank(failureMessage) && logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(VERIFICATION_PASSED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("request sequence found:{}")
                    .setArguments(verificationSequence.getHttpRequests())
            );
        }
        resultConsumer.accept(failureMessage);
    }

    /**
     * @param receivedRequests the requests as received; the returned message shows redacted copies, and the logged
     *                         entry redacts them when rendered
     */
    private String verificationSequenceFailureMessage(VerificationSequence verificationSequence, String logCorrelationId, boolean logResult, List<RequestDefinition> receivedRequests, RequestDefinition unmatchedStepRequest) {
        final List<RequestDefinition> allRequests = redactedRequestsForDisplay(receivedRequests);
        String failureMessage;
        String serializedRequestToBeVerified = requestDefinitionSerializer.serialize(true, verificationSequence.getHttpRequests());
        Integer maximumNumberOfRequestToReturnInVerificationFailure = verificationSequence.getMaximumNumberOfRequestToReturnInVerificationFailure() != null ? verificationSequence.getMaximumNumberOfRequestToReturnInVerificationFailure() : configuration.maximumNumberOfRequestToReturnInVerificationFailure();
        if (allRequests.size() < maximumNumberOfRequestToReturnInVerificationFailure) {
            String serializedAllRequestInLog = allRequests.size() == 1 ? requestDefinitionSerializer.serialize(true, allRequests.get(0)) : requestDefinitionSerializer.serialize(true, allRequests);
            failureMessage = "Request sequence not found, expected:<" + serializedRequestToBeVerified + "> but was:<" + serializedAllRequestInLog + ">";
        } else {
            failureMessage = "Request sequence not found, expected:<" + serializedRequestToBeVerified + "> but was not found, found " + allRequests.size() + " other requests";
        }
        // Mirror the single-request verify path (verifyRequest -> buildClosestMatchDiff): when detailed
        // failures are enabled and there is a recorded request to compare against, append a field-level
        // closest-match diff for the specific sequence step that failed to match, so the failure shows
        // which fields (method/path/headers/body/...) differ from the closest actual request. This is
        // diagnostic only — it never changes the pass/fail outcome (already inside the failed branch).
        if (configuration.detailedVerificationFailures() && unmatchedStepRequest instanceof HttpRequest && !allRequests.isEmpty()) {
            String diffSummary = buildClosestMatchDiff((HttpRequest) unmatchedStepRequest, allRequests);
            if (isNotBlank(diffSummary)) {
                failureMessage += diffSummary;
            }
        }
        final Object[] arguments = new Object[]{verificationSequence.getHttpRequests(), receivedRequests.size() == 1 ? receivedRequests.get(0) : receivedRequests};
        if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(VERIFICATION_FAILED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(logCorrelationId)
                    .setHttpRequests(verificationSequence.getHttpRequests().toArray(new RequestDefinition[0]))
                    .setMessageFormat("request sequence not found, expected:{}but was:{}")
                    .setArguments(arguments)
            );
        }
        return failureMessage;
    }

    /**
     * @param recordedResponses    the recorded responses as the returned failure message may show them (redacted copies)
     * @param rawRecordedResponses the same responses as recorded, for the logged entry, which redacts them when rendered
     */
    private String verificationResponseSequenceFailureMessage(VerificationSequence verificationSequence, String logCorrelationId, boolean logResult, List<HttpResponse> recordedResponses, List<HttpResponse> rawRecordedResponses, RequestDefinition unmatchedStepRequest, HttpResponse unmatchedStepResponse) {
        // for a response-aware sequence the meaningful "expected" and "actual" are the RESPONSES,
        // not the requests — serialize the expected response sequence and the recorded responses
        HttpResponseSerializer httpResponseSerializer = new HttpResponseSerializer(mockServerLogger);
        List<HttpResponse> expectedResponses = verificationSequence.getHttpResponses();
        String serializedExpectedResponses = expectedResponses.size() == 1
            ? httpResponseSerializer.serialize(expectedResponses.get(0))
            : httpResponseSerializer.serialize(expectedResponses);
        Integer maximumNumberOfRequestToReturnInVerificationFailure = verificationSequence.getMaximumNumberOfRequestToReturnInVerificationFailure() != null ? verificationSequence.getMaximumNumberOfRequestToReturnInVerificationFailure() : configuration.maximumNumberOfRequestToReturnInVerificationFailure();
        String failureMessage;
        if (recordedResponses.size() < maximumNumberOfRequestToReturnInVerificationFailure) {
            String serializedRecordedResponses = recordedResponses.size() == 1
                ? httpResponseSerializer.serialize(recordedResponses.get(0))
                : httpResponseSerializer.serialize(recordedResponses);
            failureMessage = "Response sequence not found, expected:<" + serializedExpectedResponses + "> but was:<" + serializedRecordedResponses + ">";
        } else {
            failureMessage = "Response sequence not found, expected:<" + serializedExpectedResponses + "> but was not found, found " + recordedResponses.size() + " other responses";
        }
        // Mirror the single-response verify path (verifyResponse -> buildClosestResponseMatchDiff): when
        // detailed failures are enabled and the failed step constrains the response, append a field-level
        // closest-response diff for that step against the recorded responses. Diagnostic only.
        if (configuration.detailedVerificationFailures() && unmatchedStepResponse != null && !recordedResponses.isEmpty()) {
            List<HttpResponse> nonNullRecordedResponses = recordedResponses.stream()
                .filter(Objects::nonNull)
                .limit(maximumNumberOfRequestToReturnInVerificationFailure)
                .collect(Collectors.toList());
            if (!nonNullRecordedResponses.isEmpty()) {
                Verification diffVerification = new Verification()
                    .withRequest(unmatchedStepRequest instanceof HttpRequest ? unmatchedStepRequest : null)
                    .withResponse(unmatchedStepResponse);
                String diffSummary = buildClosestResponseMatchDiff(diffVerification, unmatchedStepResponse, nonNullRecordedResponses);
                if (isNotBlank(diffSummary)) {
                    failureMessage += diffSummary;
                }
            }
        }
        if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(VERIFICATION_FAILED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("response sequence not found, expected:{}but was:{}")
                    .setArguments(expectedResponses, rawRecordedResponses)
            );
        }
        return failureMessage;
    }

    private void verificationSequenceExceptionHandler(VerificationSequence verificationSequence, Consumer<String> resultConsumer, String logCorrelationId, Throwable throwable, String s, String s2) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXCEPTION)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat(s)
                    .setArguments(throwable.getMessage(), verificationSequence)
                    .setThrowable(throwable)
            );
        }
        resultConsumer.accept(s2 + (isNotBlank(throwable.getMessage()) ? " " + throwable.getMessage() : ""));
    }

    protected String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }

}

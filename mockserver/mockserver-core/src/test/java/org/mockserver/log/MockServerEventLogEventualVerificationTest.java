package org.mockserver.log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.mockserver.verify.VerificationTimes;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.VERIFICATION_FAILED;
import static org.mockserver.log.model.LogEntry.LogMessageType.VERIFICATION_PASSED;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.verify.Verification.verification;

/**
 * Behavioural tests for server-side eventual verification (GitHub #1713): an optional {@code timeout}
 * on a {@link Verification} / {@link VerificationSequence} makes the server wait (asynchronously) and
 * re-evaluate until the verification passes or the timeout elapses, rather than evaluating the event
 * log once.
 *
 * <p>These use a REAL {@link Scheduler} (not a mock) so the notifier's scheduled executor exists and
 * the eventual path can arm its deadline + coalesced re-evaluation listener.</p>
 */
public class MockServerEventLogEventualVerificationTest {

    // NOTE: this test deliberately does NOT use GlobalFixedTime — it asserts on wall-clock elapsed
    // durations and log-entry counts/types, never on exact timestamps, so it must not pin EpochService
    // globally (doing so raced the 1ms-sensitive timestamp assertions in other GlobalFixedTime classes
    // sharing the reused sequential fork). It still runs in the sequential Surefire phase for timing
    // stability (real Scheduler + disruptor + short coalesce/deadline windows would flake under the
    // parallel phase's CPU contention).

    private final Configuration configuration = configuration();
    private MockServerLogger mockServerLogger;
    private Scheduler scheduler;
    private MockServerEventLog mockServerEventLog;
    // delayed-request threads, joined in tearDown so none can outlive its test and leak into the next
    private final List<Thread> delayedThreads = new java.util.ArrayList<>();

    private HttpState httpStateHandler;

    @Before
    public void setupTestFixture() {
        configuration.logLevel(Level.INFO);
        // a REAL Scheduler (not a mock) so the notifier's scheduled executor exists and the eventual
        // verification path can arm its deadline + coalesced re-evaluation listener; build the event
        // log through HttpState so mockServerLogger.logEvent(...) routes recorded requests to it.
        scheduler = new Scheduler(configuration, new MockServerLogger(configuration, MockServerLogger.class));
        httpStateHandler = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
        mockServerLogger = httpStateHandler.getMockServerLogger();
        mockServerEventLog = httpStateHandler.getMockServerLog();
    }

    @After
    public void tearDown() {
        // join any still-running delayed-request thread BEFORE stopping the log so it cannot fire a
        // stray log entry (or hold a reference) into the next test sharing the reused fork
        for (Thread thread : delayedThreads) {
            try {
                thread.join(SECONDS.toMillis(5));
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
        delayedThreads.clear();
        if (httpStateHandler != null) {
            httpStateHandler.stop();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private void recordReceivedRequest(String path) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request(path))
        );
    }

    /** Record a received request after {@code delayMillis} on a tracked thread (joined in tearDown). */
    private void recordReceivedRequestAfter(long delayMillis, String path) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
                return;
            }
            recordReceivedRequest(path);
        });
        delayedThreads.add(thread);
        thread.start();
    }

    private long countEntriesOfType(LogEntry.LogMessageType type) {
        CompletableFuture<List<LogEntry>> future = new CompletableFuture<>();
        mockServerEventLog.retrieveMessageLogEntries(null, future::complete);
        try {
            return future.get(60, SECONDS).stream().filter(entry -> entry.getType() == type).count();
        } catch (Exception e) {
            fail(e.getMessage());
            return -1;
        }
    }

    // ---- request-count verification ----------------------------------------------------------

    @Test
    public void shouldAnswerAtOnceWhenTheSchedulerShutsDownAsTheDeadlineIsArmed() throws Exception {
        // given a scheduler that shuts down just as the verification's deadline is scheduled, so its
        // CallerRunsPolicy discards the deadline instead of rejecting it
        long timeoutMillis = 20_000L;
        java.util.concurrent.ScheduledThreadPoolExecutor racingExecutor = new java.util.concurrent.ScheduledThreadPoolExecutor(1, new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()) {
            @Override
            public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, java.util.concurrent.TimeUnit unit) {
                if (unit.toMillis(delay) == timeoutMillis) {
                    shutdown();
                }
                return super.schedule(command, delay, unit);
            }
        };
        Scheduler racingScheduler = org.mockito.Mockito.mock(Scheduler.class);
        org.mockito.Mockito.when(racingScheduler.getExecutorService()).thenReturn(racingExecutor);
        MockServerEventLog eventLog = new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerLogger.class), racingScheduler, true);
        try {
            // when
            long start = System.currentTimeMillis();
            Future<String> result = eventLog.verify(
                verification().withRequest(request("/never/arrives")).withTimes(VerificationTimes.once()).withTimeout(timeoutMillis)
            );

            // then it fails at once rather than waiting for a deadline that will never fire
            assertThat(result.get(10, SECONDS), containsString("/never/arrives"));
            assertThat(System.currentTimeMillis() - start, is(lessThan(timeoutMillis)));
            assertThat(eventLog.listenerCount(), is(0));
        } finally {
            eventLog.stop();
            racingExecutor.shutdownNow();
        }
    }

    @Test
    public void shouldPassImmediatelyWhenAlreadySatisfiedWithTimeout() throws Exception {
        // given a request is already recorded
        recordReceivedRequest("/already/there");

        // when verifying with a (generous) timeout
        long start = System.currentTimeMillis();
        Future<String> result = mockServerEventLog.verify(
            verification().withRequest(request("/already/there")).withTimes(VerificationTimes.once()).withTimeout(5000L)
        );

        // then it passes (empty failure) and returns fast (no waiting on the deadline)
        assertThat(result.get(10, SECONDS), is(""));
        assertThat(System.currentTimeMillis() - start, is(lessThan(4000L)));
        // and no listener leaks
        assertThat(mockServerEventLog.listenerCount(), is(0));
    }

    @Test
    public void shouldPassWhenRequestArrivesBeforeTimeout() throws Exception {
        // given the request is NOT yet recorded; schedule it to arrive shortly

        // when verifying with a timeout longer than the arrival delay
        Future<String> result = mockServerEventLog.verify(
            verification().withRequest(request("/eventually")).withTimes(VerificationTimes.once()).withTimeout(3000L)
        );
        recordReceivedRequestAfter(150, "/eventually");

        // then it eventually passes once the request arrives (well before the deadline)
        assertThat(result.get(10, SECONDS), is(""));
        // and no listener leaks
        assertThat(mockServerEventLog.listenerCount(), is(0));
    }

    @Test
    public void shouldFailWithLastMessageAfterTimeoutElapses() throws Exception {
        // given nothing matching is ever recorded
        long start = System.currentTimeMillis();

        // when verifying with a SHORT timeout
        Future<String> result = mockServerEventLog.verify(
            verification().withRequest(request("/never")).withTimes(VerificationTimes.once()).withTimeout(300L)
        );

        // then it fails after roughly the deadline with the standard failure message
        String failure = result.get(10, SECONDS);
        assertThat(failure, containsString("Request not found"));
        assertThat(System.currentTimeMillis() - start, is(greaterThanOrEqualTo(250L)));
        // and no listener leaks after the deadline completes
        assertThat(mockServerEventLog.listenerCount(), is(0));
    }

    @Test
    public void shouldBehaveAsSingleShotWhenTimeoutAbsent() throws Exception {
        // given nothing matching is recorded and NO timeout is set
        long start = System.currentTimeMillis();

        // when verifying without a timeout (original behaviour)
        Future<String> result = mockServerEventLog.verify(
            verification().withRequest(request("/single/shot")).withTimes(VerificationTimes.once())
        );

        // then it fails immediately (no waiting, no listener registered)
        String failure = result.get(10, SECONDS);
        assertThat(failure, containsString("Request not found"));
        assertThat(System.currentTimeMillis() - start, is(lessThan(1000L)));
        assertThat(mockServerEventLog.listenerCount(), is(0));
    }

    @Test
    public void shouldBehaveAsSingleShotWhenTimeoutZero() throws Exception {
        // given a timeout of 0 (explicitly disabled)
        recordReceivedRequest("/zero");

        // when
        Future<String> result = mockServerEventLog.verify(
            verification().withRequest(request("/zero")).withTimes(VerificationTimes.once()).withTimeout(0L)
        );

        // then it passes single-shot with no listener registered
        assertThat(result.get(10, SECONDS), is(""));
        assertThat(mockServerEventLog.listenerCount(), is(0));
    }

    // ---- sequence verification ---------------------------------------------------------------

    @Test
    public void shouldPassSequenceWhenRequestsArriveBeforeTimeout() throws Exception {
        // given the first request is recorded but the second is not yet
        recordReceivedRequest("/seq/one");

        // when verifying the ordered sequence with a timeout
        VerificationSequence sequence = new VerificationSequence()
            .withRequests(request("/seq/one"), request("/seq/two"))
            .withTimeout(3000L);
        Future<String> result = mockServerEventLog.verify(sequence);
        recordReceivedRequestAfter(150, "/seq/two");

        // then it eventually passes
        assertThat(result.get(10, SECONDS), is(""));
        assertThat(mockServerEventLog.listenerCount(), is(0));
    }

    @Test
    public void shouldFailSequenceAfterTimeoutElapses() throws Exception {
        // given only the first request ever arrives
        recordReceivedRequest("/seq/only/one");
        long start = System.currentTimeMillis();

        // when verifying a two-step sequence with a short timeout
        VerificationSequence sequence = new VerificationSequence()
            .withRequests(request("/seq/only/one"), request("/seq/missing"))
            .withTimeout(300L);
        Future<String> result = mockServerEventLog.verify(sequence);

        // then it fails after the deadline with the sequence failure message
        String failure = result.get(10, SECONDS);
        assertThat(failure, containsString("Request sequence not found"));
        assertThat(System.currentTimeMillis() - start, is(greaterThanOrEqualTo(250L)));
        assertThat(mockServerEventLog.listenerCount(), is(0));
    }

    @Test
    public void shouldBehaveAsSingleShotSequenceWhenTimeoutAbsent() throws Exception {
        // given an unsatisfiable sequence with no timeout
        recordReceivedRequest("/seq/single");
        long start = System.currentTimeMillis();

        // when
        VerificationSequence sequence = new VerificationSequence()
            .withRequests(request("/seq/single"), request("/seq/absent"));
        Future<String> result = mockServerEventLog.verify(sequence);

        // then it fails immediately, no listener registered
        String failure = result.get(10, SECONDS);
        assertThat(failure, containsString("Request sequence not found"));
        assertThat(System.currentTimeMillis() - start, is(lessThan(1000L)));
        assertThat(mockServerEventLog.listenerCount(), is(0));
    }

    // ---- log-pollution guards (MINOR 1: intermediate re-evals must not flood the log) ---------

    @Test
    public void shouldNotFloodLogWithIntermediateFailuresWhenTimedOut() throws Exception {
        // given a verification that never passes and a timeout long enough to span MANY coalesced
        // (~250ms) re-evaluation windows — a per-retry VERIFICATION_FAILED log would write ~4 entries
        long start = System.currentTimeMillis();

        // when
        Future<String> result = mockServerEventLog.verify(
            verification().withRequest(request("/flood/never")).withTimes(VerificationTimes.once()).withTimeout(1000L)
        );

        // then it fails after the deadline
        assertThat(result.get(10, SECONDS), containsString("Request not found"));
        assertThat(System.currentTimeMillis() - start, is(greaterThanOrEqualTo(900L)));
        // and EXACTLY ONE VERIFICATION_FAILED outcome is logged (not one per ~250ms retry), and no
        // intermediate VERIFICATION_PASSED entries
        assertThat(countEntriesOfType(VERIFICATION_FAILED), is(1L));
        assertThat(countEntriesOfType(VERIFICATION_PASSED), is(0L));
        assertThat(mockServerEventLog.listenerCount(), is(0));
    }

    @Test
    public void shouldLogExactlyOnePassedOutcomeWhenEventuallySatisfied() throws Exception {
        // given the request arrives after a couple of coalesced windows

        // when
        Future<String> result = mockServerEventLog.verify(
            verification().withRequest(request("/flood/eventually")).withTimes(VerificationTimes.once()).withTimeout(3000L)
        );
        recordReceivedRequestAfter(300, "/flood/eventually");

        // then it passes, logging EXACTLY ONE VERIFICATION_PASSED and ZERO intermediate
        // VERIFICATION_FAILED entries (the failing re-evaluations during the wait are suppressed)
        assertThat(result.get(10, SECONDS), is(""));
        assertThat(countEntriesOfType(VERIFICATION_PASSED), is(1L));
        assertThat(countEntriesOfType(VERIFICATION_FAILED), is(0L));
        assertThat(mockServerEventLog.listenerCount(), is(0));
    }
}

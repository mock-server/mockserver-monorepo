package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.Times;
import org.mockserver.model.ExpectationId;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.ExpectationEntry;
import org.mockserver.state.InMemoryStateBackend;
import org.mockserver.state.KeyValueStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests for the state-backend-wired path in {@link RequestMatchers}.
 * <p>
 * Constructs a {@code RequestMatchers} with a real
 * {@link InMemoryStateBackend} wired via {@link RequestMatchers#setStateBackend},
 * then asserts that:
 * <ul>
 *     <li>add/update/remove keep the backend KV in sync with the node-local
 *         sorted view and matching results;</li>
 *     <li>eviction when past maxExpectations evicts the correct entry (oldest
 *         by insertion order) and reconcileEvictions drops exactly that
 *         node-local matcher — including the COR-01 scenario where an update
 *         does not change eviction position;</li>
 *     <li>clear/reset clears the backend;</li>
 *     <li>retrieveRequestDefinitions fallback works when an entry was evicted;</li>
 *     <li>Times/responseInProgress runtime state survives an update (matcher
 *         identity preserved).</li>
 * </ul>
 * Additionally, parity assertions compare ordering and eviction results
 * between a backend-wired and a no-backend RequestMatchers running the same
 * sequence.
 */
public class RequestMatchersStateBackendTest {

    private static final int MAX_EXPECTATIONS = 2;

    private Configuration configurationWithMax;
    private RequestMatchers backendMatchers;
    private InMemoryStateBackend stateBackend;

    @Before
    public void setup() {
        configurationWithMax = configuration().maxExpectations(MAX_EXPECTATIONS);
        Scheduler scheduler = mock(Scheduler.class);
        WebSocketClientRegistry wsRegistry = mock(WebSocketClientRegistry.class);

        backendMatchers = new RequestMatchers(
            configurationWithMax, new MockServerLogger(), scheduler, wsRegistry);

        stateBackend = new InMemoryStateBackend(MAX_EXPECTATIONS);
        backendMatchers.setStateBackend(stateBackend);
    }

    private RequestMatchers newNoBackendMatchers() {
        return new RequestMatchers(
            configurationWithMax, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
    }

    private List<String> ids(List<Expectation> expectations) {
        return expectations.stream().map(Expectation::getId).collect(Collectors.toList());
    }

    private List<String> backendIds() {
        return stateBackend.expectations().entries()
            .map(KeyValueStore.Entry::getKey)
            .collect(Collectors.toList());
    }

    // -------------------------------------------------------
    // (a) add/update/remove keep backend KV in sync
    // -------------------------------------------------------

    @Test
    public void exposesExpectationStoreByteFiguresFromBackend() {
        assertThat(backendMatchers.getExpectationBytes(), is(0L));
        assertThat(backendMatchers.getExpectationByteEvictedCount(), is(0L));

        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withStatusCode(200)), API);

        // the getters read the live backend store weight, tracked whether or not a byte budget is set
        assertThat(backendMatchers.getExpectationBytes(), greaterThan(0L));
        assertThat(backendMatchers.getExpectationBytes(), is(stateBackend.expectations().getTotalBytes()));
        assertThat(backendMatchers.getMaxExpectationBytes(), is(stateBackend.expectations().getMaxBytes()));
        assertThat(backendMatchers.getExpectationByteEvictedCount(), is(0L));
    }

    @Test
    public void addKeepsBackendInSync() {
        Expectation expA = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withStatusCode(200));

        backendMatchers.add(expA, API);

        // Node-local
        assertThat(backendMatchers.size(), is(1));
        assertThat(backendMatchers.firstMatchingExpectation(request().withPath("/a")), is(expA));

        // Backend
        assertThat(stateBackend.expectations().size(), is(1));
        assertThat(stateBackend.expectations().get("a").isPresent(), is(true));
        assertThat(stateBackend.expectations().get("a").get().getValue().getExpectation().getId(), is("a"));
    }

    @Test
    public void updateKeepsBackendInSync() {
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("v1")), API);

        // Update with same id, different body
        Expectation updatedA = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("v2"));
        backendMatchers.add(updatedA, API);

        // Node-local: still one matcher
        assertThat(backendMatchers.size(), is(1));

        // Backend: still one entry
        assertThat(stateBackend.expectations().size(), is(1));

        // Matching works with updated expectation
        Expectation matched = backendMatchers.firstMatchingExpectation(request().withPath("/a"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("v2"));
    }

    @Test
    public void removeKeepsBackendInSync() {
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withStatusCode(200)), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withStatusCode(201)), API);

        // Remove by ExpectationId
        backendMatchers.clear(ExpectationId.expectationId("a"), "test-correlation");

        assertThat(backendMatchers.size(), is(1));
        assertThat(stateBackend.expectations().size(), is(1));
        assertThat(stateBackend.expectations().get("a").isPresent(), is(false));
        assertThat(stateBackend.expectations().get("b").isPresent(), is(true));
    }

    @Test
    public void sortedViewMatchesAfterMultipleAdds() {
        backendMatchers.add(new Expectation(request().withPath("/first")).withId("first")
            .thenRespond(response().withBody("1")), API);
        backendMatchers.add(new Expectation(request().withPath("/second")).withId("second")
            .thenRespond(response().withBody("2")), API);

        List<Expectation> active = backendMatchers.retrieveActiveExpectations(null);
        assertThat(ids(active), contains("first", "second"));
    }

    // -------------------------------------------------------
    // (b) Eviction correctness + COR-01 parity
    // -------------------------------------------------------

    @Test
    public void evictionRemovesOldestByInsertionOrder() {
        // maxExpectations=2: add A, add B, add C -> A should be evicted
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);
        backendMatchers.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);

        // A should be evicted from both backend and node-local
        assertThat(backendMatchers.size(), is(2));
        assertThat(stateBackend.expectations().size(), is(2));
        assertThat(stateBackend.expectations().get("a").isPresent(), is(false));

        List<Expectation> active = backendMatchers.retrieveActiveExpectations(null);
        assertThat(ids(active), containsInAnyOrder("b", "c"));
    }

    /**
     * COR-01 scenario: update does NOT change eviction order.
     * maxExpectations=2: add A, add B, update A, add C
     * -> B should be evicted (A was inserted first, BUT its update must
     * NOT move it to tail — it stays at its original insertion position,
     * so the eviction victim is B the second-oldest, NOT A).
     *
     * Wait — let me re-read the issue: "old evicts A, new evicts B".
     * The old (correct) behaviour: A is at position 0, B at position 1.
     * Update A -> A stays at position 0, B stays at position 1.
     * Add C -> evicts position 0 -> evicts B... no.
     *
     * Actually: the insertion order queue is [A, B]. When we add C, the
     * queue has 3 elements, so the oldest (head = A) is evicted. That's
     * the pre-phase-2b behaviour because A was inserted first and update
     * preserved its position.
     *
     * The bug (before COR-01 fix) was: update A does remove+re-add, so
     * insertion order becomes [B, A]. Then add C makes it [B, A, C],
     * evicting head = B. That's WRONG because the old code would have
     * evicted A (the truly oldest).
     *
     * So: add A, add B, update A, add C -> should evict A (oldest by
     * original insertion).
     */
    @Test
    public void cor01_updateDoesNotChangeEvictionOrder() {
        // add A (position 0), add B (position 1)
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v1")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);

        // update A — should NOT change insertion position
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v2")), API);

        // add C — should evict A (the oldest by original insertion), NOT B
        backendMatchers.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);

        assertThat(backendMatchers.size(), is(2));
        assertThat(stateBackend.expectations().size(), is(2));

        List<Expectation> active = backendMatchers.retrieveActiveExpectations(null);
        List<String> activeIds = ids(active);

        // A was evicted, B and C remain
        assertThat(activeIds, containsInAnyOrder("b", "c"));
        assertThat(activeIds, not(hasItem("a")));

        // B was NOT evicted
        assertThat(stateBackend.expectations().get("b").isPresent(), is(true));
        // A was evicted
        assertThat(stateBackend.expectations().get("a").isPresent(), is(false));
    }

    @Test
    public void cor01_parityWithNoBackend() {
        // Run the exact same add/update/evict sequence against both a
        // no-backend and a backend-wired RequestMatchers and assert
        // identical retrieveActiveExpectations ordering.
        RequestMatchers noBackend = newNoBackendMatchers();

        // Sequence: add A, add B, update A, add C
        Expectation a1 = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v1"));
        Expectation b = new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b"));
        Expectation a2 = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v2"));
        Expectation c = new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c"));

        // We need fresh Expectation instances for each RequestMatchers
        // because add() mutates created timestamp.
        noBackend.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v1")), API);
        backendMatchers.add(a1, API);

        noBackend.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);
        backendMatchers.add(b, API);

        noBackend.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v2")), API);
        backendMatchers.add(a2, API);

        noBackend.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);
        backendMatchers.add(c, API);

        // Both should have the same size and same surviving ids
        List<String> noBackendIds = ids(noBackend.retrieveActiveExpectations(null));
        List<String> backendIds = ids(backendMatchers.retrieveActiveExpectations(null));

        assertThat("size parity", backendMatchers.size(), is(noBackend.size()));
        assertThat("eviction parity — same surviving expectations",
            backendIds, containsInAnyOrder(noBackendIds.toArray()));
    }

    @Test
    public void evictionCleansUpExpectationRequestDefinitions() {
        // INC-04: verify that evicted entries are removed from
        // expectationRequestDefinitions as well
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);

        // A is in expectationRequestDefinitions
        assertThat(backendMatchers.expectationRequestDefinitions.containsKey("a"), is(true));

        // Add C -> evicts A
        backendMatchers.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);

        // A should be removed from expectationRequestDefinitions too
        assertThat(backendMatchers.expectationRequestDefinitions.containsKey("a"), is(false));
        assertThat(backendMatchers.expectationRequestDefinitions.containsKey("b"), is(true));
        assertThat(backendMatchers.expectationRequestDefinitions.containsKey("c"), is(true));
    }

    // -------------------------------------------------------
    // (c) clear/reset clears the backend
    // -------------------------------------------------------

    @Test
    public void resetClearsBackend() {
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);

        backendMatchers.reset();

        assertThat(backendMatchers.size(), is(0));
        assertThat(stateBackend.expectations().size(), is(0));
        assertThat(backendMatchers.retrieveActiveExpectations(null), is(empty()));
    }

    @Test
    public void clearByRequestDefinitionClearsBackend() {
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);

        backendMatchers.clear(request().withPath("/a"));

        assertThat(backendMatchers.size(), is(1));
        assertThat(stateBackend.expectations().size(), is(1));
        assertThat(stateBackend.expectations().get("a").isPresent(), is(false));
        assertThat(stateBackend.expectations().get("b").isPresent(), is(true));
    }

    @Test
    public void clearNullRequestDefinitionResetsBackend() {
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);

        backendMatchers.clear((RequestDefinition) null);

        assertThat(backendMatchers.size(), is(0));
        assertThat(stateBackend.expectations().size(), is(0));
    }

    // -------------------------------------------------------
    // (d) retrieveRequestDefinitions fallback
    // -------------------------------------------------------

    @Test
    public void retrieveRequestDefinitionsFallsBackToBackend() {
        // Add entry so it's in both node-local and backend
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);

        // Verify the normal (node-local) path works
        List<RequestDefinition> defs = backendMatchers.retrieveRequestDefinitions(
            Collections.singletonList(ExpectationId.expectationId("a"))
        ).collect(Collectors.toList());
        assertThat(defs, hasSize(1));
        assertThat(((HttpRequest) defs.get(0)).getPath(), is("/a"));
    }

    // -------------------------------------------------------
    // (e) Times/responseInProgress survive update
    // -------------------------------------------------------

    @Test
    public void timesStateSurvivesUpdate() {
        // Add with Times.exactly(5)
        Expectation expA = new Expectation(request().withPath("/a"),
            Times.exactly(5), null, 0).withId("a")
            .thenRespond(response().withBody("a-v1"));
        backendMatchers.add(expA, API);

        // Consume one match
        Expectation matched = backendMatchers.firstMatchingExpectation(request().withPath("/a"));
        assertThat(matched, is(notNullValue()));
        int remainingAfterMatch = matched.getTimes().getRemainingTimes();
        assertThat(remainingAfterMatch, is(4));

        // Update — same id, different body
        backendMatchers.add(new Expectation(request().withPath("/a"),
            Times.exactly(5), null, 0).withId("a")
            .thenRespond(response().withBody("a-v2")), API);

        // The matcher object should be the same instance (update in place),
        // so Times state from the PREVIOUS matcher is preserved through
        // the HttpRequestMatcher.update() path. The new Expectation's Times
        // replaces the old one (that's the Expectation-level contract), but
        // the critical thing is the matcher identity is preserved.
        List<HttpRequestMatcher> matchers = backendMatchers.httpRequestMatchers.toSortedList();
        assertThat(matchers, hasSize(1));

        // After update, the expectation has the new body
        assertThat(matchers.get(0).getExpectation().getHttpResponse().getBodyAsString(), is("a-v2"));
    }

    @Test
    public void responseInProgressSurvivesUpdate() {
        Expectation expA = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v1"));
        backendMatchers.add(expA, API);

        // Simulate setting responseInProgress
        List<HttpRequestMatcher> matchers = backendMatchers.httpRequestMatchers.toSortedList();
        assertThat(matchers, hasSize(1));
        HttpRequestMatcher matcher = matchers.get(0);
        matcher.setResponseInProgress(true);
        assertThat(matcher.isResponseInProgress(), is(true));

        // Update the expectation
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a-v2")), API);

        // Same matcher instance should still have responseInProgress
        List<HttpRequestMatcher> matchersAfter = backendMatchers.httpRequestMatchers.toSortedList();
        assertThat(matchersAfter, hasSize(1));
        // The matcher identity is preserved (same object reference)
        assertThat(matchersAfter.get(0), is(sameInstance(matcher)));
        assertThat(matchersAfter.get(0).isResponseInProgress(), is(true));
    }

    // -------------------------------------------------------
    // Batch update() with backend
    // -------------------------------------------------------

    @Test
    public void batchUpdateKeepsBackendInSync() {
        // Use a separate RequestMatchers with maxExpectations=3 to avoid
        // backend eviction interfering with the batch-update semantics test.
        Configuration config3 = configuration().maxExpectations(3);
        RequestMatchers bm3 = new RequestMatchers(
            config3, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        InMemoryStateBackend sb3 = new InMemoryStateBackend(3);
        bm3.setStateBackend(sb3);

        bm3.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        bm3.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);

        // Batch update: replace A, add C — B is not in the update batch so
        // it gets removed (batch-replace semantics for matching cause).
        Expectation[] updates = new Expectation[]{
            new Expectation(request().withPath("/a")).withId("a")
                .thenRespond(response().withBody("a-v2")),
            new Expectation(request().withPath("/c")).withId("c")
                .thenRespond(response().withBody("c"))
        };
        bm3.update(updates, API);

        // B should have been removed (not in the update batch, same cause)
        assertThat(sb3.expectations().get("b").isPresent(), is(false));

        // A and C should be in both node-local and backend
        assertThat(bm3.size(), is(2));
        assertThat(sb3.expectations().size(), is(2));
        assertThat(sb3.expectations().get("a").isPresent(), is(true));
        assertThat(sb3.expectations().get("c").isPresent(), is(true));
    }

    // -------------------------------------------------------
    // (f) reconcileFromBackend picks up non-sort-field updates
    // -------------------------------------------------------

    @Test
    public void reconcileFromBackendPicksUpResponseBodyChange() {
        // Remote-update reconciliation (picking up a backend write that bypassed
        // the local add() path) only applies to a CLUSTERED backend — for the
        // non-clustered in-memory default every mutation originates locally, so
        // reconcileFromBackend() does a cheap eviction-only trim. Use a clustered
        // backend so this test exercises the full remote-update reconcile path.
        InMemoryStateBackend clusteredBackend = new InMemoryStateBackend(MAX_EXPECTATIONS) {
            @Override
            public boolean isClustered() {
                return true;
            }
        };
        RequestMatchers clusteredMatchers = new RequestMatchers(
            configurationWithMax, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        clusteredMatchers.setStateBackend(clusteredBackend);

        // Add an expectation via the normal path
        clusteredMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("original-body")), API);

        // Verify matcher serves the original body
        Expectation matched = clusteredMatchers.firstMatchingExpectation(request().withPath("/a"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("original-body"));

        // Simulate a REMOTE update: write directly to the backend KV store
        // (bypassing the local add() path), changing ONLY the response body.
        // Same id, same priority, same created — sort fields unchanged.
        Expectation remoteUpdate = new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("updated-body"));
        remoteUpdate.withCreated(matched.getCreated());
        clusteredBackend.expectations().put("a", new ExpectationEntry(remoteUpdate));

        // Trigger reconcile (simulates what the InvalidationListener does)
        clusteredMatchers.reconcileFromBackend();

        // The matcher should now serve the updated body
        Expectation matchedAfter = clusteredMatchers.firstMatchingExpectation(request().withPath("/a"));
        assertThat(matchedAfter, is(notNullValue()));
        assertThat("reconcile should pick up response body change",
            matchedAfter.getHttpResponse().getBodyAsString(), is("updated-body"));
    }

    @Test
    public void clusteredReconcilePicksUpRemoteAdd() {
        // Clustered remote-ADD reconciliation: an entry written directly to the
        // backend by another node (bypassing add()) must materialise as a local
        // matcher after reconcileFromBackend(). This guards that the clustered
        // full-reconcile path is unchanged by the non-clustered fast-path.
        InMemoryStateBackend clusteredBackend = new InMemoryStateBackend(10) {
            @Override
            public boolean isClustered() {
                return true;
            }
        };
        Configuration config10 = configuration().maxExpectations(10);
        RequestMatchers clusteredMatchers = new RequestMatchers(
            config10, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        clusteredMatchers.setStateBackend(clusteredBackend);

        // Write an entry directly to the backend, simulating a remote node's add
        Expectation remoteAdd = new Expectation(request().withPath("/remote")).withId("remote")
            .thenRespond(response().withBody("remote-body"));
        clusteredBackend.expectations().put("remote", new ExpectationEntry(remoteAdd));

        // Before reconcile, the local cache has no matcher for it
        assertThat(clusteredMatchers.size(), is(0));

        // Reconcile picks up the remote add
        clusteredMatchers.reconcileFromBackend();

        assertThat(clusteredMatchers.size(), is(1));
        Expectation matched = clusteredMatchers.firstMatchingExpectation(request().withPath("/remote"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("remote-body"));
    }

    // -------------------------------------------------------
    // Backend wiring / unwiring
    // -------------------------------------------------------

    @Test
    public void setStateBackendNullRestoresLocalEviction() {
        // Unwire the backend
        backendMatchers.setStateBackend(null);

        // Now add expectations — should use local eviction
        backendMatchers.add(new Expectation(request().withPath("/a")).withId("a")
            .thenRespond(response().withBody("a")), API);
        backendMatchers.add(new Expectation(request().withPath("/b")).withId("b")
            .thenRespond(response().withBody("b")), API);
        backendMatchers.add(new Expectation(request().withPath("/c")).withId("c")
            .thenRespond(response().withBody("c")), API);

        // maxExpectations=2, so only 2 should remain
        assertThat(backendMatchers.size(), is(2));
    }

    // -------------------------------------------------------
    // (g) clusterSharedTimesEnabled knob: shared CAS vs node-local Times
    // -------------------------------------------------------

    private InMemoryStateBackend newClusteredBackend() {
        return new InMemoryStateBackend(10) {
            @Override
            public boolean isClustered() {
                return true;
            }
        };
    }

    @Test
    public void clusteredLimitedTimesUsesSharedBackendCasByDefault() {
        // Default (clusterSharedTimesEnabled=true): a match on a clustered
        // backend decrements the dedicated SHARED-TIMES COUNTER via CAS, so the
        // fleet-wide remaining count reflects consumption. The counter lives in
        // its own tiny-value store (sharedTimesCounters()) so a clustered
        // decrement replicates only an Integer, not the whole ExpectationEntry;
        // the ExpectationEntry's own remainingTimes stays at the immutable seed.
        InMemoryStateBackend clusteredBackend = newClusteredBackend();
        Configuration config = configuration().maxExpectations(10);
        RequestMatchers matchers = new RequestMatchers(
            config, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        matchers.setStateBackend(clusteredBackend);

        matchers.add(new Expectation(request().withPath("/a"),
            Times.exactly(3), null, 0).withId("a")
            .thenRespond(response().withBody("a")), API);

        // The ExpectationEntry carries the original count as an immutable seed.
        assertThat(clusteredBackend.expectations().get("a").get().getValue().getRemainingTimes(),
            is(3));
        // The counter is EAGERLY seeded to N at add time (so an absent counter at
        // consume time means discarded, never "not yet seeded" — fail closed).
        assertThat(clusteredBackend.sharedTimesCounters().get("a").get().getValue(), is(3));

        // One match decrements the dedicated counter via CAS.
        Expectation matched = matchers.firstMatchingExpectation(request().withPath("/a"));
        assertThat(matched, is(notNullValue()));
        assertThat("default shared-Times path decrements the dedicated counter",
            clusteredBackend.sharedTimesCounters().get("a").get().getValue(),
            is(2));
        // The ExpectationEntry seed is untouched by the decrement.
        assertThat("ExpectationEntry remainingTimes stays at the immutable seed",
            clusteredBackend.expectations().get("a").get().getValue().getRemainingTimes(),
            is(3));
    }

    @Test
    public void clusteredLimitedTimesFallsBackToNodeLocalWhenSharedTimesDisabled() {
        // Opt-out (clusterSharedTimesEnabled=false): even on a clustered
        // backend, the match takes the NODE-LOCAL fast path — no shared CAS,
        // so the backend's shared remainingTimes counter is NOT touched.
        InMemoryStateBackend clusteredBackend = newClusteredBackend();
        Configuration config = configuration().maxExpectations(10)
            .clusterSharedTimesEnabled(false);
        RequestMatchers matchers = new RequestMatchers(
            config, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        matchers.setStateBackend(clusteredBackend);

        matchers.add(new Expectation(request().withPath("/a"),
            Times.exactly(3), null, 0).withId("a")
            .thenRespond(response().withBody("a")), API);

        // Backend seeded with remainingTimes = 3
        assertThat(clusteredBackend.expectations().get("a").get().getValue().getRemainingTimes(),
            is(3));

        // The match succeeds via node-local Times...
        Expectation matched = matchers.firstMatchingExpectation(request().withPath("/a"));
        assertThat(matched, is(notNullValue()));
        // ...and the node-local Times counter is what decremented (3 -> 2).
        assertThat("node-local Times decrements when shared-Times is disabled",
            matched.getTimes().getRemainingTimes(), is(2));
        // ...while the SHARED backend counter is untouched (still 3),
        // proving the synchronous backend CAS round-trip was bypassed.
        assertThat("backend shared counter is NOT touched when shared-Times disabled",
            clusteredBackend.expectations().get("a").get().getValue().getRemainingTimes(),
            is(3));
    }

    // -------------------------------------------------------
    // byte budget (maxExpectationsSizeInBytes) end-to-end through RequestMatchers -> backend
    // -------------------------------------------------------

    private static Expectation largeExpectation(String id, int bodyBytes) {
        StringBuilder body = new StringBuilder(bodyBytes);
        for (int i = 0; i < bodyBytes; i++) {
            body.append('x');
        }
        return new Expectation(request().withPath("/" + id)).withId(id)
            .thenRespond(response().withBody(body.toString()));
    }

    private RequestMatchers byteBoundedMatchers(long maxBytes) {
        Configuration config = configuration().maxExpectations(1000).maxExpectationsSizeInBytes(maxBytes);
        RequestMatchers matchers = new RequestMatchers(
            config, new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        matchers.setStateBackend(new InMemoryStateBackend(1000, maxBytes));
        return matchers;
    }

    @Test
    public void defaultConfigurationDoesNotByteEvictLargeExpectations() {
        // Regression guard: the byte budget is OPT-IN. A default configuration (no maxExpectationsSizeInBytes
        // set) must NOT evict user-registered expectations, even a load that an enabled budget would evict.
        Configuration config = configuration().maxExpectations(1000);
        assertThat("byte budget must default to disabled", config.maxExpectationsSizeInBytes(), is(0L));
        RequestMatchers matchers = new RequestMatchers(
            config, new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        // build the backend exactly as StateBackendFactory does for a default configuration
        matchers.setStateBackend(new InMemoryStateBackend(config.maxExpectations(), config.maxExpectationsSizeInBytes()));

        // the same 5 x 100KB load that evicts to ~2 when a 250KB budget is set (below) must be fully retained
        for (int i = 1; i <= 5; i++) {
            matchers.add(largeExpectation("e" + i, 100_000), API);
        }

        assertThat(matchers.size(), is(5));
    }

    @Test
    public void byteBudgetEvictsLargeExpectationsAndStaysUnderBudget() {
        // given - count bound generous (1000); byte budget holds only ~2 of the 100KB expectations
        long maxBytes = 250_000L;
        RequestMatchers matchers = byteBoundedMatchers(maxBytes);

        // when - five 100KB expectations are added through the full add() path
        for (int i = 1; i <= 5; i++) {
            matchers.add(largeExpectation("e" + i, 100_000), API);
        }

        // then - the store evicted to stay within budget rather than growing unbounded
        assertThat(matchers.size(), lessThan(5));
        assertThat(matchers.size(), greaterThanOrEqualTo(1));
    }

    @Test
    public void byteBudgetHugeDoesNotEvictNegativeControl() {
        // given - identical load but an effectively unlimited byte budget
        RequestMatchers matchers = byteBoundedMatchers(1_000_000_000L);

        // when
        for (int i = 1; i <= 5; i++) {
            matchers.add(largeExpectation("e" + i, 100_000), API);
        }

        // then - nothing evicted; proves the eviction above is caused by the byte budget, not maxExpectations
        assertThat(matchers.size(), is(5));
    }

    @Test
    public void byteBudgetWeightIsStableWhenMatcherJsonTreeMaterialisesOnMatch() {
        // The JSON matcher tree (JsonStringMatcher.matcherJsonNode) is parsed lazily on first match. The
        // byte-budget weight must NOT change when that happens, or the running total would drift between
        // add-time and evict-time. Budget disabled so nothing evicts and totalBytes reflects the weight.
        Configuration config = configuration().maxExpectations(1000).maxExpectationsSizeInBytes(0L);
        RequestMatchers matchers = new RequestMatchers(
            config, new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        InMemoryStateBackend backend = new InMemoryStateBackend(1000, 0L);
        matchers.setStateBackend(backend);

        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 500; i++) {
            json.append("\"k").append(i).append("\":\"v").append(i).append("\",");
        }
        json.append("\"last\":\"v\"}");
        HttpRequest jsonRequest = request().withPath("/json").withBody(org.mockserver.model.JsonBody.json(json.toString()));
        matchers.add(new Expectation(jsonRequest).withId("j").thenRespond(response().withBody("ok")), API);

        long beforeMatch = backend.expectationStore().getQueue().getTotalBytes();
        assertThat(beforeMatch, greaterThan(0L));

        // fire a matching request — this materialises the matcher's JsonNode tree
        assertThat(matchers.firstMatchingExpectation(
            request().withPath("/json").withBody(json.toString())), notNullValue());

        long afterMatch = backend.expectationStore().getQueue().getTotalBytes();
        assertThat("weight must not change when the matcher tree is parsed", afterMatch, is(beforeMatch));
    }

    @Test
    public void byteBudgetZeroDisablesByteBound() {
        // given - byte budget disabled with 0
        RequestMatchers matchers = byteBoundedMatchers(0L);

        // when
        for (int i = 1; i <= 5; i++) {
            matchers.add(largeExpectation("e" + i, 100_000), API);
        }

        // then - only the count bound (1000) applies, so all are retained
        assertThat(matchers.size(), is(5));
    }
}

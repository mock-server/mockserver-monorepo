package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.RequestMatchersUnmatchedDiagnosticsTest.CapturingLogger;
import org.mockserver.model.BinaryRequestDefinition;
import org.mockserver.model.DnsRequestDefinition;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.ConditionalRequestDefinition.requestIf;
import static org.mockserver.model.DnsRequestDefinition.dnsRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Pins {@code maxLoggedMatchFailures}: at INFO a request writes at most that many did-not-match entries, and nothing
 * else changes. The store mixes in expectations that write no entry for an HTTP request (DNS, binary, used-up OpenAPI),
 * one entry per operation (OpenAPI) or one per branch tried (conditional, served past the limit by its else branch).
 *
 * <p>The differential oracle is the same store and request with no limit (the default): the limited run must
 * return the same expectation, leave the same store after its lazy removals, and log the same entries in the same
 * order, apart from the did-not-match entries after the first {@code limit}, which are left out,
 * and the closest-match line, which names how many were. Instance configuration and a capturing logger only, so it
 * runs in the parallel phase.
 */
public class RequestMatchersMaxLoggedMatchFailuresTest {

    private static final int INDEXED = 2;
    private static final int UNINDEXED = 1_000_000;
    private static final String NAMESPACE_HEADER = "X-MockServer-Namespace";
    private static final Pattern RANDOM_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern NOT_LOGGED_NOTE = Pattern.compile("(\\d+) further did-not-match entries were not logged \\(maxLoggedMatchFailures\\)");

    /** Queues lazy removals so a test decides when they run. */
    private static final class QueueingScheduler extends Scheduler {
        private final List<Runnable> submitted = new ArrayList<>();

        QueueingScheduler(Configuration configuration, MockServerLogger logger) {
            super(configuration, logger, true);
        }

        @Override
        public void submit(Runnable command) {
            submitted.add(command);
        }

        void runAll() {
            List<Runnable> commands = new ArrayList<>(submitted);
            submitted.clear();
            commands.forEach(Runnable::run);
        }
    }

    private record Spec(String id, RequestDefinition request, HttpRequest exhaustingRequest, String namespace, boolean once, Integer percentage, int priority, long created) {
        Spec(String id, HttpRequest request, String namespace, boolean once, Integer percentage, int priority, long created) {
            this(id, request, request, namespace, once, percentage, priority, created);
        }

        Expectation build() {
            Expectation expectation = new Expectation(request instanceof HttpRequest httpRequest ? httpRequest.clone() : request, once ? Times.once() : Times.unlimited(), TimeToLive.unlimited(), priority)
                .withId(id)
                .withCreated(created)
                .withPercentage(percentage);
            if (!(request instanceof DnsRequestDefinition) && !(request instanceof BinaryRequestDefinition)) {
                expectation.thenRespond(response().withBody(id));
            }
            return namespace == null ? expectation : expectation.withNamespace(namespace);
        }
    }

    private static final String OPEN_API_SPEC = "---" + NEW_LINE +
        "openapi: 3.0.0" + NEW_LINE +
        "paths:" + NEW_LINE +
        "  \"/oas-path\":" + NEW_LINE +
        "    get:" + NEW_LINE +
        "      operationId: getOas" + NEW_LINE +
        "      responses:" + NEW_LINE +
        "        '200':" + NEW_LINE +
        "          description: ok" + NEW_LINE +
        "    post:" + NEW_LINE +
        "      operationId: postOas" + NEW_LINE +
        "      responses:" + NEW_LINE +
        "        '200':" + NEW_LINE +
        "          description: ok" + NEW_LINE +
        "  \"/oas-once\":" + NEW_LINE +
        "    get:" + NEW_LINE +
        "      operationId: getOasOnce" + NEW_LINE +
        "      responses:" + NEW_LINE +
        "        '200':" + NEW_LINE +
        "          description: ok" + NEW_LINE;

    /**
     * Expectations whose evaluation against an HTTP request writes no did-not-match entry (DNS, binary, a used-up
     * OpenAPI one), one that writes one per operation (OpenAPI) and one that writes one per branch it tries
     * (conditional, which a request without its header serves through its else branch).
     */
    private static List<Spec> otherKinds(long created) {
        List<Spec> specs = new ArrayList<>();
        specs.add(new Spec("dns", dnsRequest("example.com"), null, null, false, null, 0, created++));
        specs.add(new Spec("binary", binaryRequest(new byte[]{1, 2, 3}), null, null, false, null, 0, created++));
        specs.add(new Spec("oas-used-up", new OpenAPIDefinition().withSpecUrlOrPayload(OPEN_API_SPEC).withOperationId("getOasOnce"),
            request().withMethod("GET").withPath("/oas-once"), null, true, null, 0, created++));
        specs.add(new Spec("oas", new OpenAPIDefinition().withSpecUrlOrPayload(OPEN_API_SPEC), null, null, false, null, 0, created++));
        specs.add(new Spec("cond", requestIf(request().withHeader("X-Cond", "yes"), request().withPath("/cond-then"), request().withPath("/cond-else")),
            null, null, false, null, 0, created));
        return specs;
    }

    /**
     * Header-mismatch and regex candidates, a never-served (0%) expectation ahead of one that serves the same
     * path, then fillers mixing used-up {@code once()}, foreign-namespace, higher-priority and other-bucket ones.
     */
    private static List<Spec> corpus(int fillers, boolean withOtherKinds) {
        List<Spec> specs = new ArrayList<>();
        long created = 1;
        for (int i = 0; i < 3; i++) {
            specs.add(new Spec("c-hdr-" + i, request().withMethod("GET").withPath("/probe").withHeader("X-Tenant", "tenant-" + i), null, false, null, 0, created++));
        }
        specs.add(new Spec("c-regex", request().withMethod("GET").withPath("/re-[0-9]+"), null, false, null, 0, created++));
        specs.add(new Spec("pct-zero", request().withMethod("GET").withPath("/pct"), null, false, 0, 0, created++));
        if (withOtherKinds) {
            specs.addAll(otherKinds(created));
            created += 10;
        }
        for (int i = 0; i < fillers; i++) {
            String namespace = i % 7 == 4 ? "team-a" : null;
            boolean once = i % 5 == 3 && namespace == null;
            int priority = i % 11 == 6 ? 5 : 0;
            HttpRequest httpRequest = i % 13 == 9
                ? request().withMethod("POST").withPath("/probe").withHeader("X-Kind", "post-filler")
                : request().withMethod("GET").withPath("/filler/" + i);
            specs.add(new Spec(String.format("f-%04d", i), httpRequest, namespace, once, null, priority, created++));
        }
        specs.add(new Spec("pct-served", request().withMethod("GET").withPath("/pct"), null, false, null, 0, created));
        return specs;
    }

    private static List<HttpRequest> probes() {
        List<HttpRequest> probes = new ArrayList<>();
        probes.add(request().withMethod("GET").withPath("/probe").withHeader("X-Tenant", "tenant-none"));
        probes.add(request().withMethod("GET").withPath("/nowhere"));
        probes.add(request().withMethod("POST").withPath("/probe"));
        probes.add(request().withMethod("GET").withPath("/probe").withHeader("X-Tenant", "tenant-none").withHeader(NAMESPACE_HEADER, "team-b"));
        probes.add(request().withMethod("GET").withPath("/filler/x").withHeader(NAMESPACE_HEADER, "team-a"));
        // served, after a 0% expectation that matches but is skipped
        probes.add(request().withMethod("GET").withPath("/pct"));
        // served by a filler late in the store
        probes.add(request().withMethod("GET").withPath("/filler/12"));
        probes.add(request().withMethod("GET").withPath("/re-42"));
        probes.add(request().withMethod("GET").withPath("/oas-path"));
        probes.add(request().withMethod("GET").withPath("/cond-else"));
        return probes;
    }

    private record Run(String matchedId, List<String> rendered, Set<String> remainingIds) {
    }

    private static Configuration configurationFor(String logLevel, boolean detailed, boolean failFast, Integer limit) {
        return configuration().logLevel(logLevel).detailedMatchFailures(detailed).matchersFailFast(failFast)
            .matchNamespaceHeader(NAMESPACE_HEADER).maxLoggedMatchFailures(limit);
    }

    /** Loads the specs, uses up every {@code once()} one, sends the probe and returns its outcome. */
    private static Run run(Configuration configuration, List<Spec> specs, int threshold, HttpRequest probe) {
        CapturingLogger logger = new CapturingLogger(configuration);
        QueueingScheduler scheduler = new QueueingScheduler(configuration, logger);
        try {
            RequestMatchers matchers = new RequestMatchers(configuration, logger, scheduler, mock(WebSocketClientRegistry.class))
                .withCandidateIndexThreshold(threshold);
            for (Spec spec : specs) {
                matchers.add(spec.build(), API);
            }
            for (Spec spec : specs) {
                if (spec.once()) {
                    assertThat(matchers.firstMatchingExpectation(spec.exhaustingRequest().clone()).getId(), is(spec.id()));
                }
            }
            matchers.sortedRequestMatchers().forEach(matcher -> matcher.setResponseInProgress(false));
            scheduler.submitted.clear();
            logger.rendered.clear();
            Expectation matched = matchers.firstMatchingExpectation(probe.clone());
            List<String> rendered = new ArrayList<>(logger.rendered);
            scheduler.runAll();
            return new Run(matched == null ? null : matched.getId(), rendered, new HashSet<>(ids(matchers.sortedRequestMatchers())));
        } finally {
            scheduler.shutdown();
        }
    }

    private static List<String> ids(List<HttpRequestMatcher> matchers) {
        return matchers.stream().map(m -> m.getExpectation().getId()).collect(Collectors.toList());
    }

    private static boolean isClosestMatch(String entry) {
        return entry.contains("closest expectation:");
    }

    private static boolean isPerExpectationNotMatched(String entry) {
        return entry.startsWith("INFO EXPECTATION_NOT_MATCHED") && !isClosestMatch(entry);
    }

    @Test
    public void limitLeavesOutOnlyTheDidNotMatchEntriesPastItAndTheClosestMatchLineCountsThem() {
        int casesWithEntriesLeftOut = 0;
        int casesServedPastTheLimit = 0;
        for (String logLevel : new String[]{"INFO", "TRACE"}) {
            for (boolean detailed : new boolean[]{false, true}) {
                for (boolean failFast : new boolean[]{true, false}) {
                    for (int threshold : new int[]{INDEXED, UNINDEXED}) {
                        for (int fillers : new int[]{0, 3, 14, 40}) {
                          for (boolean withOtherKinds : new boolean[]{false, true}) {
                            List<Spec> specs = corpus(fillers, withOtherKinds);
                            for (HttpRequest probe : probes()) {
                                Run reference = run(configurationFor(logLevel, detailed, failFast, null), specs, threshold, probe);
                                for (int limit : new int[]{0, 1, 2, 5, 1_000_000}) {
                                    String label = logLevel + " detailed=" + detailed + " failFast=" + failFast + " threshold=" + threshold + " fillers=" + fillers + " otherKinds=" + withOtherKinds
                                        + " limit=" + limit + " probe=" + probe.getMethod().getValue() + " " + probe.getPath().getValue() + " ns=" + probe.getFirstHeader(NAMESPACE_HEADER);
                                    Run limited = run(configurationFor(logLevel, detailed, failFast, limit), specs, threshold, probe);

                                    assertThat(label + " (served)", limited.matchedId(), is(reference.matchedId()));
                                    assertThat(label + " (lazy removal)", limited.remainingIds(), is(reference.remainingIds()));

                                    List<String> expected = new ArrayList<>();
                                    int kept = 0;
                                    int leftOut = 0;
                                    for (String entry : reference.rendered()) {
                                        if (isPerExpectationNotMatched(entry) && kept++ >= limit) {
                                            leftOut++;
                                        } else {
                                            expected.add(entry);
                                        }
                                    }
                                    List<String> actual = new ArrayList<>(limited.rendered());
                                    for (int i = 0; i < actual.size(); i++) {
                                        String entry = actual.get(i);
                                        if (isClosestMatch(entry)) {
                                            Matcher note = NOT_LOGGED_NOTE.matcher(entry);
                                            if (leftOut > 0) {
                                                assertThat(label + " (note)", note.find(), is(true));
                                                assertThat(label + " (note count)", Integer.parseInt(note.group(1)), is(leftOut));
                                                actual.set(i, entry.substring(0, note.start()) + entry.substring(note.end()));
                                            } else {
                                                assertThat(label + " (no note)", note.find(), is(false));
                                            }
                                        }
                                    }
                                    assertThat(label + " (entries)", normalised(actual), is(normalised(expected)));
                                    if (leftOut > 0) {
                                        casesWithEntriesLeftOut++;
                                        if (reference.matchedId() != null) {
                                            casesServedPastTheLimit++;
                                        }
                                    }
                                }
                            }
                          }
                        }
                    }
                }
            }
        }
        assertThat(casesWithEntriesLeftOut, greaterThan(0));
        assertThat(casesServedPastTheLimit, greaterThan(0));
    }

    /**
     * The note follows the request, so the line it is removed from may differ in trailing whitespace; a conditional's
     * branches are throwaway expectations with a random id each run.
     */
    private static List<String> normalised(List<String> entries) {
        return entries.stream()
            .map(e -> isClosestMatch(e) ? e.stripTrailing() : e)
            .map(e -> RANDOM_ID.matcher(e).replaceAll("<id>"))
            .collect(Collectors.toList());
    }

    @Test
    public void noteSitsBeforeTheNotCheckedNoteWhenBothApply() {
        Configuration configuration = configurationFor("INFO", true, true, 3);
        HttpRequest probe = request().withMethod("GET").withPath("/nowhere");
        List<Spec> specs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            specs.add(new Spec(String.format("f-%04d", i), request().withMethod("GET").withPath("/filler/" + i), null, false, null, 0, i + 1));
        }
        CapturingLogger logger = new CapturingLogger(configuration);
        QueueingScheduler scheduler = new QueueingScheduler(configuration, logger);
        try {
            RequestMatchers matchers = new RequestMatchers(configuration, logger, scheduler, mock(WebSocketClientRegistry.class))
                .withCandidateIndexThreshold(INDEXED)
                .withClosestMatchMaxEvaluations(5);
            specs.forEach(spec -> matchers.add(spec.build(), API));
            logger.rendered.clear();
            assertThat(matchers.firstMatchingExpectation(probe), is((Expectation) null));
            assertThat(logger.rendered.stream().filter(RequestMatchersMaxLoggedMatchFailuresTest::isPerExpectationNotMatched).count(), is(3L));
            String closest = logger.rendered.stream().filter(RequestMatchersMaxLoggedMatchFailuresTest::isClosestMatch).findFirst().orElse(null);
            assertThat(closest, notNullValue());
            assertThat(closest, containsString("2 further did-not-match entries were not logged (maxLoggedMatchFailures); only 5 of 8 expectations were checked, so a closer one may exist"));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void zeroLogsOnlyTheClosestMatchLineForAThousandExpectations() {
        HttpRequest probe = request().withMethod("GET").withPath("/nowhere");
        List<Spec> specs = new ArrayList<>();
        for (int i = 0; i < RequestMatchers.DEFAULT_CLOSEST_MATCH_MAX_EVALUATIONS; i++) {
            specs.add(new Spec(String.format("f-%04d", i), request().withMethod("GET").withPath("/filler/" + i), null, false, null, 0, i + 1));
        }
        Run limited = run(configurationFor("INFO", true, true, 0), specs, RequestMatchers.DEFAULT_CANDIDATE_INDEX_THRESHOLD, probe);
        assertThat(limited.rendered().size(), is(1));
        assertThat(limited.rendered().get(0), startsWith("INFO EXPECTATION_NOT_MATCHED"));
        assertThat(limited.rendered().get(0), containsString("\"id\" : \"f-0000\""));
        assertThat(limited.rendered().get(0), containsString("1000 further did-not-match entries were not logged (maxLoggedMatchFailures)"));
        assertThat(limited.rendered().get(0), not(containsString("expectations were checked")));

        Run unlimited = run(configurationFor("INFO", true, true, -1), specs, RequestMatchers.DEFAULT_CANDIDATE_INDEX_THRESHOLD, probe);
        assertThat(unlimited.rendered().stream().filter(RequestMatchersMaxLoggedMatchFailuresTest::isPerExpectationNotMatched).count(), is(1000L));
        assertThat(unlimited.rendered().stream().filter(RequestMatchersMaxLoggedMatchFailuresTest::isClosestMatch).findFirst().orElse(""), not(containsString("maxLoggedMatchFailures")));
    }

    @Test
    public void defaultIsNoLimit() {
        assertThat(new Configuration().maxLoggedMatchFailures(), is(-1));
    }
}

package org.mockserver.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.NO_MATCH_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * {@code PUT /mockserver/explain?correlationId=} re-checks one recorded request against the current expectations,
 * in the order the serving path evaluates them, and says why each expectation ahead of the winner lost.
 */
public class HttpStateExplainRequestTest {

    private HttpState httpState;
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Before
    public void setUp() {
        Configuration configuration = configuration().logLevel(Level.INFO);
        Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(configuration, HttpStateExplainRequestTest.class), true);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, HttpStateExplainRequestTest.class), scheduler);
    }

    @After
    public void stopHttpState() {
        if (httpState != null) {
            httpState.stop();
        }
    }

    @Test
    public void shouldExplainWhyAHigherPriorityExpectationLost() throws Exception {
        httpState.add(
            new Expectation(request().withMethod("GET").withPath("/orders").withHeader("X-Tenant", "gold"))
                .withId("high").withPriority(10).thenRespond(response()),
            new Expectation(request().withMethod("GET").withPath("/orders"))
                .withId("low").thenRespond(response())
        );
        recordMatched("c1", request().withMethod("GET").withPath("/orders").withHeader("X-Tenant", "silver"), "low", afterLastChange());

        JsonNode result = explain("c1");

        assertThat(result.path("matchedExpectation").path("expectationId").asText(), is("low"));
        JsonNode loser = result.path("skippedExpectations").get(0);
        assertThat(loser.path("expectationId").asText(), is("high"));
        assertThat(loser.path("reason").asText(), is("FIELDS_DIFFER"));
        assertThat(loser.path("priority").asInt(), is(10));
        assertThat(loser.path("differences").has("headers"), is(true));
        assertThat(loser.path("explanation").asText(), containsString("headers"));
        assertThat(result.path("skippedExpectations").size(), is(1));
        assertThat(result.path("atArrival").path("outcome").asText(), is("MATCHED"));
        assertThat(result.path("atArrival").path("expectationId").asText(), is("low"));
        assertThat(result.path("sameOutcomeAsAtArrival").asBoolean(), is(true));
        assertThat(result.path("expectationsChangedSince").asBoolean(), is(false));
        assertThat(result.path("recomputed").asBoolean(), is(true));
        assertThat(result.path("request").path("path").asText(), is("/orders"));
    }

    @Test
    public void shouldNameTheLowerPriorityExpectationsTheWinnerShadows() throws Exception {
        httpState.add(
            new Expectation(request().withPath("/orders")).withId("first").withPriority(5).thenRespond(response()),
            new Expectation(request().withPath("/orders")).withId("second").thenRespond(response()),
            new Expectation(request().withPath("/other")).withId("unrelated").thenRespond(response())
        );
        recordMatched("c2", request().withMethod("GET").withPath("/orders"), "first", afterLastChange());

        JsonNode result = explain("c2");

        assertThat(result.path("matchedExpectation").path("expectationId").asText(), is("first"));
        JsonNode shadowed = result.path("shadowedExpectations");
        assertThat(shadowed.size(), is(1));
        assertThat(shadowed.get(0).path("expectationId").asText(), is("second"));
        assertThat(shadowed.get(0).path("reason").asText(), is("LOWER_PRIORITY"));
        assertThat(shadowed.get(0).path("explanation").asText(), containsString("higher priority (5 > 0)"));
    }

    @Test
    public void shouldExplainAnExpectationWaitingForAnotherScenarioState() throws Exception {
        httpState.add(
            new Expectation(request().withPath("/basket")).withId("paid").withPriority(10)
                .withScenarioName("checkout").withScenarioState("Paid").thenRespond(response()),
            new Expectation(request().withPath("/basket")).withId("fallback").thenRespond(response())
        );
        recordMatched("c3", request().withMethod("GET").withPath("/basket"), "fallback", afterLastChange());

        JsonNode loser = explain("c3").path("skippedExpectations").get(0);

        assertThat(loser.path("expectationId").asText(), is("paid"));
        assertThat(loser.path("reason").asText(), is("SCENARIO_STATE"));
        assertThat(loser.path("explanation").asText(), containsString("state 'Paid'"));
        assertThat(loser.path("explanation").asText(), containsString("now in state 'Started'"));
    }

    @Test
    public void shouldExplainAnExpectationInAnotherNamespace() throws Exception {
        httpState.add(
            new Expectation(request().withPath("/users")).withId("tenant").withPriority(10).withNamespace("team-a").thenRespond(response()),
            new Expectation(request().withPath("/users")).withId("global").thenRespond(response())
        );
        recordMatched("c4", request().withMethod("GET").withPath("/users"), "global", afterLastChange());

        JsonNode loser = explain("c4").path("skippedExpectations").get(0);

        assertThat(loser.path("expectationId").asText(), is("tenant"));
        assertThat(loser.path("reason").asText(), is("NAMESPACE"));
        assertThat(loser.path("explanation").asText(), containsString("namespace 'team-a'"));
    }

    @Test
    public void shouldExplainAnInactiveAndAPercentageGatedExpectation() throws Exception {
        httpState.add(
            new Expectation(request().withPath("/items"), Times.unlimited(), TimeToLive.exactly(TimeUnit.MILLISECONDS, 1L), 20)
                .withId("expired").thenRespond(response()),
            new Expectation(request().withPath("/items")).withId("sometimes").withPriority(10).withPercentage(25).thenRespond(response()),
            new Expectation(request().withPath("/items")).withId("always").thenRespond(response())
        );
        Thread.sleep(5);
        recordMatched("c5", request().withMethod("GET").withPath("/items"), "always", afterLastChange());

        JsonNode skipped = explain("c5").path("skippedExpectations");

        assertThat(reasonOf(skipped, "expired"), is("INACTIVE"));
        assertThat(reasonOf(skipped, "sometimes"), is("PERCENTAGE"));
    }

    @Test
    public void shouldReportTheClosestExpectationWhenNothingMatchesNow() throws Exception {
        httpState.add(
            new Expectation(request().withMethod("POST").withPath("/far/away")).withId("far").thenRespond(response()),
            new Expectation(request().withMethod("GET").withPath("/orders").withHeader("X-Token", "a")).withId("near").thenRespond(response())
        );
        HttpRequest received = request().withMethod("GET").withPath("/orders").withHeader("X-Token", "b");
        long arrival = afterLastChange();
        httpState.log(received("c6", received, arrival));
        httpState.log(new LogEntry().setType(NO_MATCH_RESPONSE).setLogLevel(Level.INFO).setCorrelationId("c6").setEpochTime(arrival)
            .setHttpRequest(received).setHttpResponse(response().withStatusCode(404)));

        JsonNode result = explain("c6");

        assertThat(result.has("matchedExpectation"), is(false));
        assertThat(result.path("closestExpectationId").asText(), is("near"));
        assertThat(result.path("atArrival").path("outcome").asText(), is("NO_MATCH"));
        assertThat(result.path("sameOutcomeAsAtArrival").asBoolean(), is(true));
        assertThat(result.path("skippedExpectations").size(), is(2));
    }

    @Test
    public void shouldExplainAFailedForwardAsMatchedByTheForwardExpectationWithTheReasonItFailed() throws Exception {
        httpState.add(new Expectation(request().withMethod("GET").withPath("/stock")).withId("stock-forward")
            .thenForward(org.mockserver.model.HttpForward.forward().withHost("127.0.0.1").withPort(1)));
        HttpRequest received = request().withMethod("GET").withPath("/stock");
        recordFailedForward("f1", received, "stock-forward", "the upstream refused the connection", true, afterLastChange());

        JsonNode result = explain("f1");

        JsonNode arrival = result.path("atArrival");
        assertThat(result.toString(), arrival.path("outcome").asText(), is("MATCHED"));
        assertThat(arrival.path("expectationId").asText(), is("stock-forward"));
        assertThat(result.path("sameOutcomeAsAtArrival").asBoolean(), is(true));
        assertThat(arrival.path("forwardFailure").path("statusCode").asInt(), is(502));
        assertThat(arrival.path("forwardFailure").path("reason").asText(), is("the upstream refused the connection"));
        assertThat(arrival.path("forwardFailure").path("causeNamed").asBoolean(), is(true));
        assertThat(arrival.path("forwardFailure").path("forwardLoopSuspected").asBoolean(), is(false));
    }

    @Test
    public void shouldExplainAFailedProxyOfAnUnmatchedRequestAsForwardedWithoutAnExpectation() throws Exception {
        HttpRequest received = request().withMethod("GET").withPath("/proxied");
        recordFailedForward("f2", received, null, "connection refused", false, afterLastChange());

        JsonNode result = explain("f2");

        JsonNode arrival = result.path("atArrival");
        assertThat(result.toString(), arrival.path("outcome").asText(), is("FORWARDED_WITHOUT_EXPECTATION"));
        assertThat(arrival.has("expectationId"), is(false));
        assertThat(result.path("sameOutcomeAsAtArrival").asBoolean(), is(true));
        assertThat(arrival.path("forwardFailure").path("reason").asText(), is("connection refused"));
        assertThat(arrival.path("forwardFailure").path("causeNamed").asBoolean(), is(false));
        assertThat(arrival.path("forwardFailure").path("hint").asText(), containsString("retrieve_logs"));
    }

    @Test
    public void shouldNotReportAForwardFailureForAGenuineMiss() throws Exception {
        HttpRequest received = request().withMethod("GET").withPath("/missed");
        long arrival = afterLastChange();
        httpState.log(received("f3", received, arrival));
        httpState.log(new LogEntry().setType(NO_MATCH_RESPONSE).setLogLevel(Level.INFO).setCorrelationId("f3").setEpochTime(arrival)
            .setHttpRequest(received).setHttpResponse(response().withStatusCode(404)));

        JsonNode result = explain("f3");

        assertThat(result.path("atArrival").path("outcome").asText(), is("NO_MATCH"));
        assertThat(result.path("atArrival").has("forwardFailure"), is(false));
    }

    @Test
    public void shouldSayWhenTheExpectationThatServedTheRequestHasGoneAndTheExpectationsChanged() throws Exception {
        httpState.add(new Expectation(request().withPath("/once"), Times.once(), TimeToLive.unlimited(), 0).withId("once").thenRespond(response()));
        // the arrival shares the add's millisecond, so the changes below can never be recorded before it
        long arrival = httpState.getRequestMatchers().lastModifiedEpochMillis();
        httpState.getRequestMatchers().clear(org.mockserver.model.ExpectationId.expectationId("once"), "test");
        httpState.add(new Expectation(request().withPath("/once")).withId("replacement").thenRespond(response()));
        recordMatched("c7", request().withMethod("GET").withPath("/once"), "once", arrival);

        JsonNode result = explain("c7");

        assertThat(result.path("atArrival").path("expectationStillStored").asBoolean(), is(false));
        assertThat(result.path("matchedExpectation").path("expectationId").asText(), is("replacement"));
        assertThat(result.path("sameOutcomeAsAtArrival").asBoolean(), is(false));
        assertThat(result.path("expectationsChangedSince").asBoolean(), is(true));
        assertThat(result.has("expectationsLastChanged"), is(true));
    }

    @Test
    public void shouldNotConsumeTimesOrWriteMatchEvents() throws Exception {
        httpState.add(new Expectation(request().withPath("/limited"), Times.exactly(1), TimeToLive.unlimited(), 0).withId("limited").thenRespond(response()),
            new Expectation(request().withPath("/elsewhere")).withId("miss").withPriority(5).thenRespond(response()));
        recordMatched("c8", request().withMethod("GET").withPath("/limited"), "limited", afterLastChange());
        int matchEventsBefore = matchEventCount();

        for (int i = 0; i < 3; i++) {
            assertThat(explain("c8").path("matchedExpectation").path("expectationId").asText(), is("limited"));
        }

        Expectation limited = httpState.getRequestMatchers().retrieveActiveExpectations(null).stream()
            .filter(expectation -> "limited".equals(expectation.getId())).findFirst().orElseThrow();
        assertThat(limited.getTimes().getRemainingTimes(), is(1));
        assertThat(matchEventCount(), is(matchEventsBefore));
    }

    @Test
    public void shouldStopAtTheEvaluationBudgetAndSaySo() throws Exception {
        List<Expectation> expectations = new ArrayList<>();
        for (int i = 0; i <= HttpState.EXPLAIN_REQUEST_EVALUATION_BUDGET; i++) {
            expectations.add(new Expectation(request().withPath("/n/" + i)).withId("e" + i).thenRespond(response()));
        }
        httpState.add(expectations.toArray(new Expectation[0]));
        recordMatched("c9", request().withMethod("GET").withPath("/none"), null, afterLastChange());

        JsonNode result = explain("c9");

        assertThat(result.path("truncated").asBoolean(), is(true));
        assertThat(result.path("evaluatedExpectations").asInt(), is(HttpState.EXPLAIN_REQUEST_EVALUATION_BUDGET));
        assertThat(result.path("totalExpectations").asInt(), is(HttpState.EXPLAIN_REQUEST_EVALUATION_BUDGET + 1));
    }

    @Test
    public void shouldFlagABodyTheEventLogTruncated() throws Exception {
        httpState.add(new Expectation(request().withPath("/upload")).withId("upload").thenRespond(response()));
        recordMatched("c10", request().withMethod("POST").withPath("/upload")
            .withHeader(MockServerEventLog.TRUNCATED_BODY_HEADER, "100000").withBody("abc"), "upload", afterLastChange());

        JsonNode result = explain("c10");

        assertThat(result.path("request").path("bodyTruncated").asBoolean(), is(true));
        assertThat(result.path("matchedExpectation").path("expectationId").asText(), is("upload"));
    }

    @Test
    public void shouldAcceptTheCorrelationIdInTheBody() throws Exception {
        httpState.add(new Expectation(request().withPath("/body")).withId("body").thenRespond(response()));
        recordMatched("c11", request().withMethod("GET").withPath("/body"), "body", afterLastChange());

        HttpResponse response = awaitExplained(request("/mockserver/explain").withMethod("PUT").withBody("{\"correlationId\":\"c11\"}"));

        assertThat(response.getStatusCode(), is(200));
        assertThat(objectMapper.readTree(response.getBodyAsString()).path("matchedExpectation").path("expectationId").asText(), is("body"));
    }

    @Test
    public void shouldAnswer404ForAnUnknownCorrelationIdAnd400WithoutOne() throws Exception {
        HttpResponse unknown = httpState.explain(request("/mockserver/explain").withMethod("PUT").withQueryStringParameter("correlationId", "never-seen"));
        assertThat(unknown.getStatusCode(), is(404));
        assertThat(unknown.getBodyAsString(), containsString("no request is recorded"));

        HttpResponse missing = httpState.explain(request("/mockserver/explain").withMethod("PUT"));
        assertThat(missing.getStatusCode(), is(400));
        assertThat(missing.getBodyAsString(), containsString("requires a correlationId"));
    }

    @Test
    public void shouldReturnExactlyTheFieldsTheOpenApiSchemaDocuments() throws Exception {
        httpState.add(
            new Expectation(request().withMethod("GET").withPath("/schema").withHeader("X-Tenant", "gold"))
                .withId("high").withPriority(10).thenRespond(response()),
            new Expectation(request().withPath("/schema")).withId("served").withPriority(5).thenRespond(response()),
            new Expectation(request().withPath("/schema")).withId("shadowed").thenRespond(response()),
            new Expectation(request().withMethod("POST").withPath("/schema/far")).withId("far").thenRespond(response())
        );
        recordMatched("s1", request().withMethod("GET").withPath("/schema").withHeader("X-Tenant", "silver"), "served", afterLastChange());
        recordMatched("s2", request().withMethod("POST").withPath("/schema/near")
            .withHeader(MockServerEventLog.TRUNCATED_BODY_HEADER, "100000").withBody("abc"), null, afterLastChange());
        List<Expectation> many = new ArrayList<>();
        for (int i = 0; i <= HttpState.EXPLAIN_REQUEST_EVALUATION_BUDGET; i++) {
            many.add(new Expectation(request().withPath("/budget/" + i)).withId("b" + i).thenRespond(response()));
        }
        httpState.add(many.toArray(new Expectation[0]));
        recordMatched("s3", request().withMethod("GET").withPath("/budget/none"), null, afterLastChange());
        recordFailedForward("s4", request().withMethod("GET").withPath("/schema"), "served", "x".repeat(ForwardFailureExplainer.MAX_REASON_CHARS + 1), false, afterLastChange());

        OpenApiResponseSchema schema = OpenApiResponseSchema.of("/mockserver/explain", "200");
        for (String correlationId : new String[]{"s1", "s2", "s3", "s4"}) {
            schema.assertDocuments(explain(correlationId));
        }
        httpState.getConfiguration().llmExplainNarrationEnabled(true);
        schema.assertDocuments(narrated("s1"));
        httpState.setLlmCompletionService(new org.mockserver.llm.client.LlmCompletionService((llmRequest, timeoutMillis) -> response().withStatusCode(200)
                .withBody("{\"model\":\"gpt-4o-mini\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"summary\"}}]}")),
            org.mockserver.llm.client.LlmBackend.of(org.mockserver.model.Provider.OPENAI, "key"));
        schema.assertDocuments(narrated("s1"));

        // explainUnmatched alone fills suggestedFix; HttpStateDiagnosticsOpenApiSchemaTest covers it
        assertThat("documented but never returned", schema.documentedButNeverReturned(path -> path.startsWith("ExplainedExpectation/suggestedFix") || path.startsWith("SuggestedFix/")),
            is(java.util.Collections.emptyList()));
    }

    // ---- helpers ----

    /** An arrival time after the last expectation change, so the expectations count as unchanged since. */
    private long afterLastChange() {
        return httpState.getRequestMatchers().lastModifiedEpochMillis() + 1;
    }

    private static LogEntry received(String correlationId, HttpRequest request, long arrival) {
        return new LogEntry().setType(RECEIVED_REQUEST).setLogLevel(Level.INFO).setCorrelationId(correlationId)
            .setEpochTime(arrival).setHttpRequest(request);
    }

    private void recordMatched(String correlationId, HttpRequest request, String expectationId, long arrival) {
        httpState.log(received(correlationId, request, arrival));
        if (expectationId != null) {
            httpState.log(new LogEntry().setType(EXPECTATION_RESPONSE).setLogLevel(Level.INFO).setCorrelationId(correlationId)
                .setEpochTime(arrival).setHttpRequest(request).setHttpResponse(response()).setExpectationId(expectationId));
        }
    }

    /** What a failed forward records: the request, then its 502 entry, with the forward expectation's id when one matched. */
    private void recordFailedForward(String correlationId, HttpRequest request, String expectationId, String reason, boolean causeNamed, long arrival) {
        httpState.log(received(correlationId, request, arrival));
        HttpResponse badGateway = response().withStatusCode(502);
        if (causeNamed) {
            badGateway.withBody(reason);
        }
        httpState.log(new LogEntry().setType(NO_MATCH_RESPONSE).setLogLevel(Level.INFO).setCorrelationId(correlationId).setEpochTime(arrival)
            .setHttpRequest(request).setHttpResponse(badGateway).setExpectationId(expectationId)
            .setMessageFormat(org.mockserver.log.model.LogEntryMessages.FORWARD_FAILURE_MESSAGE_FORMAT).setArguments(request, reason, badGateway));
    }

    private JsonNode explain(String correlationId) throws Exception {
        HttpResponse response = awaitExplained(request("/mockserver/explain").withMethod("PUT").withQueryStringParameter("correlationId", correlationId));
        assertThat(response.getBodyAsString(), response.getStatusCode(), is(200));
        return objectMapper.readTree(response.getBodyAsString());
    }

    private JsonNode narrated(String correlationId) throws Exception {
        HttpResponse response = httpState.explainWithOptionalNarration(request("/mockserver/explain").withMethod("PUT")
            .withQueryStringParameter("correlationId", correlationId).withQueryStringParameter("narrate", "true")).get(10, TimeUnit.SECONDS);
        assertThat(response.getBodyAsString(), response.getStatusCode(), is(200));
        return objectMapper.readTree(response.getBodyAsString());
    }

    /** The log is written asynchronously: retry a 404 until the recorded entries are visible, within a deadline. */
    private HttpResponse awaitExplained(HttpRequest explainRequest) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        HttpResponse response = httpState.explain(explainRequest);
        while (response.getStatusCode() == 404 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            response = httpState.explain(explainRequest);
        }
        return response;
    }

    private int matchEventCount() throws Exception {
        CompletableFuture<List<LogEntry>> entries = new CompletableFuture<>();
        httpState.getMockServerLog().retrieveLogEntriesInReverseForUI(null,
            entry -> entry.getType() == EXPECTATION_MATCHED || entry.getType() == EXPECTATION_NOT_MATCHED,
            java.util.function.Function.identity(),
            stream -> entries.complete(stream.collect(java.util.stream.Collectors.toList())));
        return entries.get(5, TimeUnit.SECONDS).size();
    }

    private static String reasonOf(JsonNode nodes, String expectationId) {
        for (JsonNode node : nodes) {
            if (expectationId.equals(node.path("expectationId").asText())) {
                return node.path("reason").asText();
            }
        }
        throw new AssertionError("no entry for expectation '" + expectationId + "' in " + nodes);
    }
}

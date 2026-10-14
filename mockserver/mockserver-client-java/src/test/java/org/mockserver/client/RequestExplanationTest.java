package org.mockserver.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class RequestExplanationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void shouldReadEveryFieldOfAnExplanation() throws Exception {
        RequestExplanation explanation = RequestExplanation.fromJson(objectMapper.readTree("{"
            + "\"correlationId\":\"c1\",\"timestamp\":\"2026-10-13T10:00:00Z\",\"recomputed\":true,\"note\":\"recomputed now\","
            + "\"request\":{\"timestamp\":\"2026-10-13 10:00:00.000\",\"method\":\"GET\",\"path\":\"/orders\",\"bodyTruncated\":true,\"bodyTruncatedNote\":\"cut\"},"
            + "\"atArrival\":{\"outcome\":\"MATCHED\",\"expectationId\":\"low\",\"expectationStillStored\":false},"
            + "\"sameOutcomeAsAtArrival\":false,\"expectationsChangedSince\":true,\"expectationsLastChanged\":\"2026-10-13T10:00:01Z\","
            + "\"matchedExpectation\":{\"expectationId\":\"now\",\"position\":2,\"priority\":0,\"matches\":true,\"differingFieldCount\":0},"
            + "\"skippedExpectations\":[{\"expectationId\":\"high\",\"expectationMethod\":\"GET\",\"expectationPath\":\"/orders\",\"position\":1,\"priority\":10,"
            + "\"reason\":\"FIELDS_DIFFER\",\"explanation\":\"the request differs on: headers\",\"matches\":false,\"differingFieldCount\":1,"
            + "\"differences\":{\"headers\":[\"expected gold\"]},\"remediation\":{\"headers\":\"check X-Tenant\"}}],"
            + "\"shadowedExpectations\":[{\"expectationId\":\"later\",\"position\":3,\"priority\":0,\"reason\":\"LOWER_PRIORITY\",\"explanation\":\"also matches\"}],"
            + "\"totalExpectations\":600,\"evaluatedExpectations\":500,\"truncated\":true,\"maxExpectationsEvaluated\":500}"));

        assertThat(explanation.correlationId(), is("c1"));
        assertThat(explanation.note(), is("recomputed now"));
        assertThat(explanation.request().path(), is("/orders"));
        assertThat(explanation.request().bodyTruncated(), is(true));
        assertThat(explanation.atArrival().outcome(), is(RequestExplanation.Outcome.MATCHED));
        assertThat(explanation.atArrival().expectationStillStored(), is(false));
        assertThat(explanation.atArrival().forwardFailure(), is(nullValue()));
        assertThat(explanation.sameOutcomeAsAtArrival(), is(false));
        assertThat(explanation.expectationsChangedSince(), is(true));
        assertThat(explanation.expectationsLastChanged(), is("2026-10-13T10:00:01Z"));
        assertThat(explanation.matchedExpectation().expectationId(), is("now"));
        assertThat(explanation.matchedExpectation().reason(), is(nullValue()));
        RequestExplanation.ExplainedExpectation skipped = explanation.skippedExpectations().get(0);
        assertThat(skipped.reason(), is(RequestExplanation.Reason.FIELDS_DIFFER));
        assertThat(skipped.priority(), is(10));
        assertThat(skipped.differingFieldCount(), is(1));
        assertThat(skipped.differences().get("headers"), is(List.of("expected gold")));
        assertThat(skipped.remediation().get("headers"), is("check X-Tenant"));
        assertThat(explanation.shadowedExpectations().get(0).reason(), is(RequestExplanation.Reason.LOWER_PRIORITY));
        assertThat(explanation.totalExpectations(), is(600));
        assertThat(explanation.evaluatedExpectations(), is(500));
        assertThat(explanation.truncated(), is(true));
        assertThat(explanation.maxExpectationsEvaluated(), is(500));
    }

    @Test
    public void shouldToleratePartsANewerOrOlderServerLeavesOutOrRenames() throws Exception {
        RequestExplanation explanation = RequestExplanation.fromJson(objectMapper.readTree("{"
            + "\"correlationId\":\"c2\",\"atArrival\":{\"outcome\":\"SOMETHING_NEW\"},"
            + "\"closestExpectationId\":\"near\","
            + "\"skippedExpectations\":[{\"expectationId\":\"x\",\"reason\":\"A_NEW_GATE\"}]}"));

        assertThat(explanation.atArrival().outcome(), is(RequestExplanation.Outcome.UNKNOWN));
        assertThat(explanation.atArrival().forwardFailure(), is(nullValue()));
        assertThat(explanation.matchedExpectation(), is(nullValue()));
        assertThat(explanation.closestExpectationId(), is("near"));
        assertThat(explanation.skippedExpectations().get(0).reason(), is(RequestExplanation.Reason.UNKNOWN));
        assertThat(explanation.skippedExpectations().get(0).differences(), is(Collections.emptyMap()));
        assertThat(explanation.shadowedExpectations(), is(Collections.emptyList()));
        assertThat(explanation.sameOutcomeAsAtArrival(), is(nullValue()));
        assertThat(explanation.maxExpectationsEvaluated(), is(nullValue()));
    }

    @Test
    public void shouldReadTheReasonAFailedForwardGaveAtArrival() throws Exception {
        RequestExplanation explanation = RequestExplanation.fromJson(objectMapper.readTree("{"
            + "\"correlationId\":\"c3\",\"atArrival\":{\"outcome\":\"MATCHED\",\"expectationId\":\"stock-forward\",\"expectationStillStored\":true,"
            + "\"forwardFailure\":{\"statusCode\":502,\"reason\":\"Connection refused\",\"reasonTruncated\":true,"
            + "\"causeNamed\":false,\"forwardLoopSuspected\":true,\"hint\":\"retrieve_logs with this correlationId\"}}}"));

        RequestExplanation.ForwardFailure failure = explanation.atArrival().forwardFailure();
        assertThat(explanation.atArrival().expectationId(), is("stock-forward"));
        assertThat(failure.statusCode(), is(502));
        assertThat(failure.reason(), is("Connection refused"));
        assertThat(failure.reasonTruncated(), is(true));
        assertThat(failure.causeNamed(), is(false));
        assertThat(failure.forwardLoopSuspected(), is(true));
        assertThat(failure.hint(), is("retrieve_logs with this correlationId"));
    }

    @Test
    public void shouldLeaveOutTheForwardFailureFieldsAServerDidNotSend() throws Exception {
        RequestExplanation explanation = RequestExplanation.fromJson(objectMapper.readTree("{"
            + "\"correlationId\":\"c4\",\"atArrival\":{\"outcome\":\"FORWARDED_WITHOUT_EXPECTATION\",\"forwardFailure\":{\"reason\":\"timed out\",\"causeNamed\":true}}}"));

        RequestExplanation.ForwardFailure failure = explanation.atArrival().forwardFailure();
        assertThat(failure.reason(), is("timed out"));
        assertThat(failure.causeNamed(), is(true));
        assertThat(failure.statusCode(), is(nullValue()));
        assertThat(failure.reasonTruncated(), is(false));
        assertThat(failure.forwardLoopSuspected(), is(nullValue()));
        assertThat(failure.hint(), is(nullValue()));
    }

    @Test
    public void shouldKeepTheThreeArgumentArrivalConstructor() {
        RequestExplanation.Arrival arrival = new RequestExplanation.Arrival(RequestExplanation.Outcome.NO_MATCH, null, null);

        assertThat(arrival.outcome(), is(RequestExplanation.Outcome.NO_MATCH));
        assertThat(arrival.forwardFailure(), is(nullValue()));
    }
}

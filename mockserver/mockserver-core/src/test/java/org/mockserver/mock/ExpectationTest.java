package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class ExpectationTest {

    @Test
    public void shouldConstructAndGetFields() {
        // given
        HttpRequest httpRequest = new HttpRequest();
        HttpResponse httpResponse = new HttpResponse();
        HttpForward httpForward = new HttpForward();
        HttpError httpError = new HttpError();
        HttpClassCallback httpClassCallback = new HttpClassCallback();
        HttpObjectCallback httpObjectCallback = new HttpObjectCallback();
        Times times = Times.exactly(3);
        TimeToLive timeToLive = TimeToLive.exactly(TimeUnit.HOURS, 5L);
        int priority = 10;

        // when
        Expectation expectationThatResponds = new Expectation(httpRequest, times, timeToLive, priority).thenRespond(httpResponse);

        // then
        assertThat(expectationThatResponds.getHttpRequest(), is(httpRequest));
        assertThat(expectationThatResponds.getHttpResponse(), is(httpResponse));
        assertThat(expectationThatResponds.getAction(), is(httpResponse));
        assertThat(expectationThatResponds.getHttpForward(), nullValue());
        assertThat(expectationThatResponds.getHttpError(), nullValue());
        assertThat(expectationThatResponds.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationThatResponds.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationThatResponds.getTimes(), is(times));
        assertThat(expectationThatResponds.getTimeToLive(), is(timeToLive));
        assertThat(expectationThatResponds.getPriority(), is(priority));

        // when
        Expectation expectationThatForwards = new Expectation(httpRequest, times, timeToLive, priority).thenForward(httpForward);

        // then
        assertThat(expectationThatForwards.getHttpRequest(), is(httpRequest));
        assertThat(expectationThatForwards.getHttpResponse(), nullValue());
        assertThat(expectationThatForwards.getHttpForward(), is(httpForward));
        assertThat(expectationThatForwards.getAction(), is(httpForward));
        assertThat(expectationThatForwards.getHttpError(), nullValue());
        assertThat(expectationThatForwards.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationThatForwards.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationThatForwards.getTimes(), is(times));
        assertThat(expectationThatForwards.getTimeToLive(), is(timeToLive));
        assertThat(expectationThatForwards.getPriority(), is(priority));

        // when
        Expectation expectationThatErrors = new Expectation(httpRequest, times, timeToLive, priority).thenError(httpError);

        // then
        assertThat(expectationThatErrors.getHttpRequest(), is(httpRequest));
        assertThat(expectationThatErrors.getHttpResponse(), nullValue());
        assertThat(expectationThatErrors.getHttpForward(), nullValue());
        assertThat(expectationThatErrors.getHttpError(), is(httpError));
        assertThat(expectationThatErrors.getAction(), is(httpError));
        assertThat(expectationThatErrors.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationThatErrors.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationThatErrors.getTimes(), is(times));
        assertThat(expectationThatErrors.getTimeToLive(), is(timeToLive));
        assertThat(expectationThatErrors.getPriority(), is(priority));

        // when
        Expectation expectationThatCallsbacksClass = new Expectation(httpRequest, times, timeToLive, priority).thenRespond(httpClassCallback);

        // then
        assertThat(expectationThatForwards.getHttpRequest(), is(httpRequest));
        assertThat(expectationThatCallsbacksClass.getHttpResponse(), nullValue());
        assertThat(expectationThatCallsbacksClass.getHttpForward(), nullValue());
        assertThat(expectationThatCallsbacksClass.getHttpError(), nullValue());
        assertThat(expectationThatCallsbacksClass.getHttpResponseClassCallback(), is(httpClassCallback));
        assertThat(expectationThatCallsbacksClass.getAction(), is(httpClassCallback));
        assertThat(expectationThatCallsbacksClass.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationThatCallsbacksClass.getTimes(), is(times));
        assertThat(expectationThatCallsbacksClass.getTimeToLive(), is(timeToLive));
        assertThat(expectationThatCallsbacksClass.getPriority(), is(priority));

        // when
        Expectation expectationThatCallsbackObject = new Expectation(httpRequest, times, timeToLive, priority).thenRespond(httpObjectCallback);

        // then
        assertThat(expectationThatForwards.getHttpRequest(), is(httpRequest));
        assertThat(expectationThatCallsbackObject.getHttpResponse(), nullValue());
        assertThat(expectationThatCallsbackObject.getHttpForward(), nullValue());
        assertThat(expectationThatCallsbackObject.getHttpError(), nullValue());
        assertThat(expectationThatCallsbackObject.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationThatCallsbackObject.getHttpResponseObjectCallback(), is(httpObjectCallback));
        assertThat(expectationThatCallsbackObject.getAction(), is(httpObjectCallback));
        assertThat(expectationThatCallsbackObject.getTimes(), is(times));
        assertThat(expectationThatCallsbackObject.getTimeToLive(), is(timeToLive));
        assertThat(expectationThatCallsbackObject.getPriority(), is(priority));
    }

    @Test
    public void shouldAllowForNulls() {
        // when
        Expectation expectation = new Expectation(null, null, null, 0).thenRespond((HttpResponse) null).thenForward((HttpForward) null).thenRespond((HttpClassCallback) null).thenRespond((HttpObjectCallback) null);

        // then
        assertThat(expectation.isActive(), is(true));
        assertThat(expectation.contains(null), is(false));
        assertThat(expectation.getHttpRequest(), nullValue());
        assertThat(expectation.getHttpResponse(), nullValue());
        assertThat(expectation.getHttpForward(), nullValue());
        assertThat(expectation.getHttpResponseClassCallback(), nullValue());
        assertThat(expectation.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectation.getTimes(), nullValue());
        assertThat(expectation.getTimeToLive(), nullValue());
        assertThat(0, is(expectation.getPriority()));
    }

    @Test
    public void shouldNormaliseBlankNamespaceToGlobal() {
        // a non-blank namespace is trimmed and retained
        assertThat(new Expectation(request()).withNamespace("team-a").getNamespace(), is("team-a"));
        assertThat(new Expectation(request()).withNamespace("  team-a  ").getNamespace(), is("team-a"));

        // null / empty / whitespace-only namespace is normalised to null (global),
        // so a blank namespace is unambiguously global and can never produce an
        // expectation that matches everything yet cannot be cleared by namespace
        assertThat(new Expectation(request()).withNamespace(null).getNamespace(), nullValue());
        assertThat(new Expectation(request()).withNamespace("").getNamespace(), nullValue());
        assertThat(new Expectation(request()).withNamespace("   ").getNamespace(), nullValue());
    }

    @Test
    public void shouldReturnAliveStatus() {
        // when no times left should return false
        assertThat(new Expectation(null, Times.exactly(0), TimeToLive.unlimited(), 0).thenRespond((HttpResponse) null).thenForward((HttpForward) null).isActive(), is(false));
        assertThat(new Expectation(request(), Times.exactly(0), TimeToLive.unlimited(), 0).thenRespond((HttpResponse) null).thenForward((HttpForward) null).isActive(), is(false));
        assertThat(new Expectation(request().withPath("un-matching"), Times.exactly(0), TimeToLive.unlimited(), 0).thenRespond((HttpResponse) null).thenForward((HttpForward) null).isActive(), is(false));

        // when ttl expired should return false
        assertThat(new Expectation(null, Times.unlimited(), TimeToLive.exactly(TimeUnit.MICROSECONDS, 0L), 0).thenRespond((HttpResponse) null).thenForward((HttpForward) null).isActive(), is(false));
        assertThat(new Expectation(request(), Times.unlimited(), TimeToLive.exactly(TimeUnit.MICROSECONDS, 0L), 0).thenRespond((HttpResponse) null).thenForward((HttpForward) null).isActive(), is(false));
        assertThat(new Expectation(request().withPath("un-matching"), Times.unlimited(), TimeToLive.exactly(TimeUnit.MICROSECONDS, 0L), 0).thenRespond((HttpResponse) null).thenForward((HttpForward) null).isActive(), is(false));
    }

    @Test
    public void shouldReduceRemainingMatches() {
        // given
        Expectation expectation = new Expectation(null, Times.once(), TimeToLive.unlimited(), 0);

        // when
        expectation.decrementRemainingMatches();

        // then
        assertThat(expectation.getTimes().getRemainingTimes(), is(0));
    }

    @Test
    public void shouldCalculateRemainingMatches() {
        assertThat(new Expectation(null, Times.once(), TimeToLive.unlimited(), 0).isActive(), is(true));
        assertThat(new Expectation(null, Times.unlimited(), TimeToLive.unlimited(), 0).isActive(), is(true));
        assertThat(new Expectation(null, Times.exactly(1), TimeToLive.unlimited(), 0).isActive(), is(true));
        assertThat(new Expectation(null, null, TimeToLive.unlimited(), 0).isActive(), is(true));

        assertThat(new Expectation(null, Times.exactly(0), TimeToLive.unlimited(), 0).isActive(), is(false));
    }

    @Test
    public void shouldCalculateRemainingLife() {
        assertThat(new Expectation(null, Times.unlimited(), TimeToLive.unlimited(), 0).isActive(), is(true));
        assertThat(new Expectation(null, Times.unlimited(), TimeToLive.exactly(TimeUnit.MINUTES, 5L), 0).isActive(), is(true));
        assertThat(new Expectation(null, Times.unlimited(), null, 0).isActive(), is(true));

        assertThat(new Expectation(null, Times.unlimited(), TimeToLive.exactly(TimeUnit.MICROSECONDS, 0L), 0).isActive(), is(false));
    }

    @Test
    public void shouldNotThrowExceptionWithReducingNullRemainingMatches() {
        // given
        Expectation expectation = new Expectation(null, null, TimeToLive.unlimited(), 0);

        // when
        expectation.decrementRemainingMatches();

        // then
        assertThat(expectation.getTimes(), nullValue());
    }

    @Test
    public void shouldAllowResponseAfterForward() {
        // given
        HttpRequest httpRequest = new HttpRequest();
        HttpResponse httpResponse = new HttpResponse();
        HttpForward httpForward = new HttpForward();

        // when
        Expectation expectation = new Expectation(httpRequest, Times.once(), TimeToLive.unlimited(), 0).thenForward(httpForward).thenRespond(httpResponse);

        // then
        assertThat(expectation.getHttpResponse(), is(httpResponse));
        assertThat(expectation.getHttpForward(), is(httpForward));
    }

    @Test
    public void shouldAllowResponseAfterError() {
        // given
        HttpRequest httpRequest = new HttpRequest();
        HttpResponse httpResponse = new HttpResponse();
        HttpError httpError = new HttpError();

        // when
        Expectation expectation = new Expectation(httpRequest, Times.once(), TimeToLive.unlimited(), 0).thenError(httpError).thenRespond(httpResponse);

        // then
        assertThat(expectation.getHttpResponse(), is(httpResponse));
        assertThat(expectation.getHttpError(), is(httpError));
    }

    @Test
    public void shouldAllowForwardAfterResponse() {
        // given
        HttpRequest httpRequest = new HttpRequest();
        HttpResponse httpResponse = new HttpResponse();
        HttpForward httpForward = new HttpForward();

        // when
        Expectation expectation = new Expectation(httpRequest, Times.once(), TimeToLive.unlimited(), 0).thenRespond(httpResponse).thenForward(httpForward);

        // then
        assertThat(expectation.getHttpResponse(), is(httpResponse));
        assertThat(expectation.getHttpForward(), is(httpForward));
    }

    @Test
    public void shouldReturnPrimaryActionWhenFlagged() {
        // given
        HttpResponse httpResponse = response().withPrimary(false);
        HttpForward httpForward = new HttpForward().withHost("localhost").withPrimary(true);

        // when
        Expectation expectation = new Expectation(request())
            .thenRespond(httpResponse)
            .thenForward(httpForward);

        // then
        assertThat(expectation.getPrimaryAction(), is(httpForward));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenMultipleActionsAndNoPrimary() {
        // given
        HttpResponse httpResponse = response();
        HttpForward httpForward = new HttpForward().withHost("localhost");

        // when
        Expectation expectation = new Expectation(request())
            .thenRespond(httpResponse)
            .thenForward(httpForward);

        // then - throws
        expectation.getPrimaryAction();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenMultipleActionsAndMultiplePrimaries() {
        // given
        HttpResponse httpResponse = response().withPrimary(true);
        HttpForward httpForward = new HttpForward().withHost("localhost").withPrimary(true);

        // when
        Expectation expectation = new Expectation(request())
            .thenRespond(httpResponse)
            .thenForward(httpForward);

        // then - throws
        expectation.getPrimaryAction();
    }

    @Test
    public void shouldReturnSecondaryActions() {
        // given
        HttpResponse httpResponse = response().withPrimary(true);
        HttpForward httpForward = new HttpForward().withHost("localhost");

        // when
        Expectation expectation = new Expectation(request())
            .thenRespond(httpResponse)
            .thenForward(httpForward);

        // then
        assertThat(expectation.getSecondaryActions().size(), is(1));
        assertThat(expectation.getSecondaryActions().get(0), is(httpForward));
    }

    @Test
    public void shouldReturnEmptySecondaryActionsForSingleAction() {
        // given
        HttpResponse httpResponse = response();

        // when
        Expectation expectation = new Expectation(request())
            .thenRespond(httpResponse);

        // then
        assertThat(expectation.getSecondaryActions().size(), is(0));
    }

    @Test
    public void shouldReturnEmptySecondaryActionsForNoAction() {
        // when
        Expectation expectation = new Expectation(request());

        // then
        assertThat(expectation.getSecondaryActions().size(), is(0));
    }

    @Test
    public void shouldGetActionDelegatesToPrimaryAction() {
        // given
        HttpResponse httpResponse = response().withPrimary(false);
        HttpForward httpForward = new HttpForward().withHost("localhost").withPrimary(true);

        // when
        Expectation expectation = new Expectation(request())
            .thenRespond(httpResponse)
            .thenForward(httpForward);

        // then
        assertThat(expectation.getAction(), is(httpForward));
        assertThat(expectation.getAction(), is(expectation.getPrimaryAction()));
    }

    @Test
    public void shouldReturnNullPrimaryActionWhenNoActions() {
        // when
        Expectation expectation = new Expectation(request());

        // then
        assertThat(expectation.getPrimaryAction(), nullValue());
        assertThat(expectation.getAction(), nullValue());
    }

    @Test
    public void shouldSetAndGetPercentage() {
        Expectation expectation = new Expectation(request()).withPercentage(50);
        assertThat(expectation.getPercentage(), is(50));
    }

    @Test
    public void shouldReturnNullPercentageByDefault() {
        Expectation expectation = new Expectation(request());
        assertThat(expectation.getPercentage(), nullValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectPercentageBelow0() {
        new Expectation(request()).withPercentage(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectPercentageAbove100() {
        new Expectation(request()).withPercentage(101);
    }

    @Test
    public void shouldAcceptPercentageBoundaryValues() {
        assertThat(new Expectation(request()).withPercentage(0).getPercentage(), is(0));
        assertThat(new Expectation(request()).withPercentage(100).getPercentage(), is(100));
    }

    @Test
    public void shouldMatchByPercentageWhenNull() {
        assertThat(new Expectation(request()).matchesByPercentage(), is(true));
    }

    @Test
    public void shouldMatchByPercentageWhen100() {
        assertThat(new Expectation(request()).withPercentage(100).matchesByPercentage(), is(true));
    }

    @Test
    public void shouldNotMatchByPercentageWhen0() {
        assertThat(new Expectation(request()).withPercentage(0).matchesByPercentage(), is(false));
    }

    @Test
    public void shouldMatchByPercentageStatistically() {
        Expectation expectation = new Expectation(request()).withPercentage(50);
        int matchCount = 0;
        int iterations = 10000;
        for (int i = 0; i < iterations; i++) {
            if (expectation.matchesByPercentage()) {
                matchCount++;
            }
        }
        assertThat("Expected ~50% matches but got " + matchCount, matchCount > 3000 && matchCount < 7000, is(true));
    }

    @Test
    public void shouldIncludePercentageInClone() {
        Expectation original = new Expectation(request()).withPercentage(75).thenRespond(response());
        Expectation clone = original.clone();
        assertThat(clone.getPercentage(), is(75));
    }

    @Test
    public void shouldIncludePercentageInEquals() {
        Expectation a = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0).withPercentage(50);
        Expectation b = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0).withPercentage(50);
        Expectation c = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0).withPercentage(75);
        assertThat(b, is(a));
        assertThat(a.equals(c), is(false));
    }

    @Test
    public void shouldIncludePercentageInHashCode() {
        Expectation a = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0).withPercentage(50);
        Expectation b = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0).withPercentage(50);
        Expectation c = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0).withPercentage(75);
        assertThat(b.hashCode(), is(a.hashCode()));
        assertThat(c.hashCode(), not(sameInstance(a.hashCode())));
    }

    @Test
    public void shouldSelectSequentialResponsesByDefault() {
        HttpResponse r1 = response("one");
        HttpResponse r2 = response("two");
        HttpResponse r3 = response("three");
        Expectation expectation = new Expectation(request())
            .thenRespond(Arrays.asList(r1, r2, r3));

        expectation.consumeMatch();
        assertThat(((HttpResponse) expectation.getAction()).getBodyAsString(), is("one"));

        expectation.consumeMatch();
        assertThat(((HttpResponse) expectation.getAction()).getBodyAsString(), is("two"));

        expectation.consumeMatch();
        assertThat(((HttpResponse) expectation.getAction()).getBodyAsString(), is("three"));

        expectation.consumeMatch();
        assertThat(((HttpResponse) expectation.getAction()).getBodyAsString(), is("one"));
    }

    @Test
    public void shouldSelectSequentialResponsesExplicitly() {
        HttpResponse r1 = response("a");
        HttpResponse r2 = response("b");
        Expectation expectation = new Expectation(request())
            .thenRespond(Arrays.asList(r1, r2))
            .withResponseMode(ResponseMode.SEQUENTIAL);

        expectation.consumeMatch();
        assertThat(((HttpResponse) expectation.getAction()).getBodyAsString(), is("a"));

        expectation.consumeMatch();
        assertThat(((HttpResponse) expectation.getAction()).getBodyAsString(), is("b"));

        expectation.consumeMatch();
        assertThat(((HttpResponse) expectation.getAction()).getBodyAsString(), is("a"));
    }

    @Test
    public void shouldSelectRandomResponses() {
        HttpResponse r1 = response("x");
        HttpResponse r2 = response("y");
        HttpResponse r3 = response("z");
        Expectation expectation = new Expectation(request())
            .thenRespond(Arrays.asList(r1, r2, r3))
            .withResponseMode(ResponseMode.RANDOM);

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            expectation.consumeMatch();
            seen.add(((HttpResponse) expectation.getAction()).getBodyAsString());
        }
        assertThat("Expected multiple different responses in RANDOM mode, but got: " + seen, seen.size() > 1, is(true));
    }

    @Test
    public void shouldReturnSingleResponseFromHttpResponses() {
        HttpResponse r1 = response("only");
        Expectation expectation = new Expectation(request())
            .thenRespond(Arrays.asList(r1));

        expectation.consumeMatch();
        assertThat(((HttpResponse) expectation.getAction()).getBodyAsString(), is("only"));

        expectation.consumeMatch();
        assertThat(((HttpResponse) expectation.getAction()).getBodyAsString(), is("only"));
    }

    @Test
    public void shouldPrioritizeHttpResponsesOverHttpResponse() {
        HttpResponse single = response("single");
        HttpResponse multi1 = response("multi1");
        HttpResponse multi2 = response("multi2");
        Expectation expectation = new Expectation(request())
            .thenRespond(single)
            .thenRespond(Arrays.asList(multi1, multi2));

        expectation.consumeMatch();
        assertThat(((HttpResponse) expectation.getAction()).getBodyAsString(), is("multi1"));
    }

    @Test
    public void shouldFallBackToHttpResponseWhenHttpResponsesEmpty() {
        HttpResponse single = response("fallback");
        Expectation expectation = new Expectation(request()).thenRespond(single);

        assertThat(((HttpResponse) expectation.getAction()).getBodyAsString(), is("fallback"));
    }

    @Test
    public void shouldIncludeHttpResponsesInClone() {
        HttpResponse r1 = response("one");
        HttpResponse r2 = response("two");
        Expectation original = new Expectation(request())
            .thenRespond(Arrays.asList(r1, r2))
            .withResponseMode(ResponseMode.RANDOM);
        Expectation clone = original.clone();

        assertThat(clone.getHttpResponses().size(), is(2));
        assertThat(clone.getResponseMode(), is(ResponseMode.RANDOM));
    }

    @Test
    public void shouldIncludeHttpResponsesInEquals() {
        HttpResponse r1 = response("one");
        HttpResponse r2 = response("two");
        Expectation a = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(r1, r2))
            .withResponseMode(ResponseMode.SEQUENTIAL);
        Expectation b = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(r1, r2))
            .withResponseMode(ResponseMode.SEQUENTIAL);
        Expectation c = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(r1, r2))
            .withResponseMode(ResponseMode.RANDOM);
        assertThat(b, is(a));
        assertThat(a.equals(c), is(false));
    }

    @Test
    public void shouldTrackMatchCount() {
        Expectation expectation = new Expectation(request()).thenRespond(response());

        assertThat(expectation.getMatchCount(), is(0));

        expectation.consumeMatch();
        assertThat(expectation.getMatchCount(), is(1));

        expectation.consumeMatch();
        assertThat(expectation.getMatchCount(), is(2));

        expectation.consumeMatch();
        assertThat(expectation.getMatchCount(), is(3));
    }

    @Test
    public void shouldNotIncrementMatchCountWhenTimesExhausted() {
        Expectation expectation = new Expectation(request(), Times.exactly(2), TimeToLive.unlimited(), 0)
            .thenRespond(response());

        assertThat(expectation.consumeMatch(), is(true));
        assertThat(expectation.getMatchCount(), is(1));

        assertThat(expectation.consumeMatch(), is(true));
        assertThat(expectation.getMatchCount(), is(2));

        assertThat(expectation.consumeMatch(), is(false));
        assertThat(expectation.getMatchCount(), is(2));
    }

    @Test
    public void shouldIncludeScenarioFieldsInClone() {
        Expectation original = new Expectation(request())
            .thenRespond(response())
            .withScenarioName("TestScenario")
            .withScenarioState("Started")
            .withNewScenarioState("Step2");
        Expectation clone = original.clone();

        assertThat(clone.getScenarioName(), is("TestScenario"));
        assertThat(clone.getScenarioState(), is("Started"));
        assertThat(clone.getNewScenarioState(), is("Step2"));
    }

    @Test
    public void shouldIncludeScenarioFieldsInEquals() {
        Expectation a = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .withScenarioName("S1")
            .withScenarioState("Started");
        Expectation b = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .withScenarioName("S1")
            .withScenarioState("Started");
        Expectation c = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .withScenarioName("S1")
            .withScenarioState("Different");
        assertThat(b, is(a));
        assertThat(a.equals(c), is(false));
    }

    @Test
    public void shouldAnchorChaosFirstMatchEpochOnceOnFirstMatch() {
        try {
            org.mockserver.time.TimeService.freeze(java.time.Instant.ofEpochMilli(1_000_000L));
            Expectation expectation = new Expectation(request());
            // before any match the anchor is unset
            assertThat(expectation.getChaosFirstMatchEpochMillis(), is(0L));
            // first match records the anchor via the controllable clock
            assertThat(expectation.consumeMatch(), is(true));
            assertThat(expectation.getChaosFirstMatchEpochMillis(), is(1_000_000L));
            // advancing the clock and matching again must NOT move the anchor (set-once)
            org.mockserver.time.TimeService.advance(java.time.Duration.ofMillis(5_000L));
            assertThat(expectation.consumeMatch(), is(true));
            assertThat(expectation.getChaosFirstMatchEpochMillis(), is(1_000_000L));
        } finally {
            org.mockserver.time.TimeService.reset();
        }
    }

    @Test
    public void shouldEstimateHeapSizeGrowingWithBodyAndMemoizeIt() {
        // given
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            large.append('x');
        }
        Expectation small = Expectation.when(request("/a")).thenRespond(response().withBody("x"));
        Expectation big = Expectation.when(request("/a")).thenRespond(response().withBody(large.toString()));

        // when
        long smallSize = small.estimatedHeapSize();
        long bigSize = big.estimatedHeapSize();

        // then - the large body dominates the estimate, and the raw body bytes are counted
        assertThat(bigSize, greaterThan(smallSize));
        assertThat(bigSize, greaterThanOrEqualTo(100_000L));

        // and - memoized: repeated calls return the identical value (add-time weight == evict-time weight)
        assertThat(small.estimatedHeapSize(), is(smallSize));
        assertThat(big.estimatedHeapSize(), is(bigSize));
    }

    @Test
    public void shouldCountRequestMatcherBodyInHeapEstimate() {
        // given - the request matcher body is the dominant retained cost the byte budget exists to bound
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 50_000; i++) {
            large.append('y');
        }
        Expectation withRequestBody = Expectation.when(request("/a").withBody(large.toString())).thenRespond(response().withBody("ok"));
        Expectation withoutRequestBody = Expectation.when(request("/a")).thenRespond(response().withBody("ok"));

        // then
        assertThat(withRequestBody.estimatedHeapSize(), greaterThan(withoutRequestBody.estimatedHeapSize() + 40_000L));
    }

    @Test
    public void shouldCountParsedJsonMatcherTreeForJsonRequestBody() {
        // given - a JSON request body is parsed into a retained JsonNode matcher tree (the dominant heap
        // term); an identical-length plain string body is not, so the JSON estimate must be much larger
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 1000; i++) {
            json.append("\"key").append(i).append("\":\"value").append(i).append("\",");
        }
        json.append("\"last\":\"value\"}");
        String jsonText = json.toString();

        Expectation jsonReq = Expectation.when(request("/a").withBody(org.mockserver.model.JsonBody.json(jsonText)))
            .thenRespond(response().withBody("ok"));
        Expectation stringReq = Expectation.when(request("/a").withBody(new org.mockserver.model.StringBody(jsonText)))
            .thenRespond(response().withBody("ok"));

        // then - the JSON estimate exceeds the string estimate by roughly the tree-expansion factor
        assertThat(jsonReq.estimatedHeapSize(),
            greaterThan(stringReq.estimatedHeapSize() + 10L * jsonText.length()));
    }

    @Test
    public void shouldNotChangeHeapEstimateAfterRuntimeStateMutation() {
        // given
        Expectation expectation = Expectation.when(request("/a")).thenRespond(response().withBody("body"));
        long before = expectation.estimatedHeapSize();

        // when - a runtime match mutates match/rotation counters, none of which are counted
        expectation.consumeMatch();

        // then - the weight is unchanged (stable between add and evict)
        assertThat(expectation.estimatedHeapSize(), is(before));
    }
}

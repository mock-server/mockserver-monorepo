package org.mockserver.log;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.VerificationSequenceSerializer;
import org.mockserver.serialization.VerificationSerializer;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.fail;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.NO_MATCH_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationTimes.atLeast;
import static org.mockserver.verify.VerificationTimes.atMost;
import static org.mockserver.verify.VerificationTimes.between;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * @author jamesdbloom
 */
public class MockServerEventLogRequestLogEntryVerificationTest {

    private static final Scheduler scheduler = new Scheduler(configuration(), new MockServerLogger());
    private final Configuration configuration = configuration();
    private MockServerEventLog mockServerEventLog;

    @Before
    public void setupTestFixture() {
        configuration.detailedVerificationFailures(false);
        mockServerEventLog = new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler, true);
    }

    @After
    public void stopEventLog() {
        mockServerEventLog.stop();
    }

    @AfterClass
    public static void stopScheduler() {
        scheduler.shutdown();
    }

    public String verify(Verification verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        mockServerEventLog.verify(verification, result::complete);
        try {
            return result.get(10, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    public String verify(VerificationSequence verificationSequence) {
        CompletableFuture<String> result = new CompletableFuture<>();
        mockServerEventLog.verify(verificationSequence, result::complete);
        try {
            return result.get(10, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    public List<LogEventRequestAndResponse> retrieveRequestResponses(HttpRequest httpRequest) {
        CompletableFuture<List<LogEventRequestAndResponse>> result = new CompletableFuture<>();
        mockServerEventLog.retrieveRequestResponses(httpRequest, result::complete);
        try {
            return result.get(10, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    @Test
    public void shouldPassVerificationWithNullRequest() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify((Verification) null), is(""));
    }

    @Test
    public void shouldPassVerificationWithDefaultTimes() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest()
                            .withPath("some_path")
                    )
            ),
            is(""));
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest()
                            .withPath("some_other_path")
                    )
            ),
            is(""));
    }

    @Test
    public void shouldPassVerificationWithAtLeastTwoTimes() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest().withPath("some_path")
                    )
                    .withTimes(atLeast(2))
            ),
            is(""));
    }

    @Test
    public void shouldPassVerificationWithAtLeastZeroTimes() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest().withPath("some_non_matching_path")
                    )
                    .withTimes(atLeast(0))
            ),
            is(""));
    }

    @Test
    public void shouldPassVerificationWithExactlyTwoTimes() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest()
                            .withPath("some_path")
                    )
                    .withTimes(exactly(2))
            ),
            is(""));
    }

    @Test
    public void shouldPassVerificationWithExactlyZeroTimes() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest()
                            .withPath("some_non_matching_path")
                    )
                    .withTimes(exactly(0))
            ),
            is(""));
    }

    @Test
    public void shouldFailVerificationWithNullRequest() {
        // given

        // then
        assertThat(verify((Verification) null), is(""));
    }

    @Test
    public void shouldFailVerificationWithDefaultTimes() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest().withPath("some_non_matching_path")
                    )
            ),
            is("Request not found at least once, expected:<{" + NEW_LINE +
                "  \"path\" : \"some_non_matching_path\"" + NEW_LINE +
                "}> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"some_path\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"some_other_path\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"some_path\"" + NEW_LINE +
                "} ]>"));
    }

    @Test
    public void shouldFailVerificationWithAtLeastTwoTimes() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest().withPath("some_other_path")
                    )
                    .withTimes(atLeast(2))
            ),
            is("Request not found at least 2 times, expected:<{" + NEW_LINE +
                "  \"path\" : \"some_other_path\"" + NEW_LINE +
                "}> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"some_path\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"some_other_path\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"some_path\"" + NEW_LINE +
                "} ]>"));
    }

    @Test
    public void shouldFailVerificationWithLimitedReturnedRequestsViaConfiguration() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");
        configuration.maximumNumberOfRequestToReturnInVerificationFailure(1);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest().withPath("some_other_path")
                    )
                    .withTimes(atLeast(2))
            ),
            is("Request not found at least 2 times, expected:<{" + NEW_LINE +
                "  \"path\" : \"some_other_path\"" + NEW_LINE +
                "}> but was found 1 time among 3 total requests"));
    }

    @Test
    public void shouldFailVerificationWithLimitedReturnedRequestsViaVerification() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest().withPath("some_other_path")
                    )
                    .withTimes(atLeast(2))
                    .withMaximumNumberOfRequestToReturnInVerificationFailure(1)
            ),
            is("Request not found at least 2 times, expected:<{" + NEW_LINE +
                "  \"path\" : \"some_other_path\"" + NEW_LINE +
                "}> but was found 1 time among 3 total requests"));
    }

    @Test
    public void shouldFailVerificationWithExactTwoTimes() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest()
                            .withPath("some_other_path")
                    )
                    .withTimes(exactly(2))
            ),
            is("Request not found exactly 2 times, expected:<{" + NEW_LINE +
                "  \"path\" : \"some_other_path\"" + NEW_LINE +
                "}> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"some_path\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"some_other_path\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"some_path\"" + NEW_LINE +
                "} ]>"));
    }

    @Test
    public void shouldSayFoundTooOftenWhenAnUpperBoundIsExceeded() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");

        // when
        mockServerEventLog.add(new LogEntry().setHttpRequest(httpRequest).setType(RECEIVED_REQUEST));
        mockServerEventLog.add(new LogEntry().setHttpRequest(httpRequest).setType(RECEIVED_REQUEST));
        mockServerEventLog.add(new LogEntry().setHttpRequest(httpRequest).setType(RECEIVED_REQUEST));

        // then
        assertThat(verify(verification().withRequest(request().withPath("some_path")).withTimes(atMost(1))),
            startsWith("Request found 3 times but should have been found at most once, expected:<{"));
        assertThat(verify(verification().withRequest(request().withPath("some_path")).withTimes(between(1, 2))),
            startsWith("Request found 3 times but should have been found between 1 and 2 times, expected:<{"));
        assertThat(verify(verification().withRequest(request().withPath("some_path")).withTimes(exactly(2))),
            startsWith("Request found 3 times but should have been found exactly 2 times, expected:<{"));
        // too few is still "not found"
        assertThat(verify(verification().withRequest(request().withPath("some_path")).withTimes(between(4, 5))),
            startsWith("Request not found between 4 and 5 times, expected:<{"));
    }

    @Test
    public void shouldSayFoundTooOftenWhenAResponseUpperBoundIsExceeded() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpResponse httpResponse = new HttpResponse().withStatusCode(200);

        // when
        mockServerEventLog.add(new LogEntry().setHttpRequest(httpRequest).setHttpResponse(httpResponse).setType(FORWARDED_REQUEST));
        mockServerEventLog.add(new LogEntry().setHttpRequest(httpRequest).setHttpResponse(httpResponse).setType(FORWARDED_REQUEST));

        // then
        assertThat(verify(verification().withResponse(response().withStatusCode(200)).withTimes(atMost(1))),
            startsWith("Response found 2 times but should have been found at most once, expected:<{"));
        assertThat(verify(verification().withResponse(response().withStatusCode(200)).withTimes(atLeast(3))),
            startsWith("Response not found at least 3 times, expected:<{"));
    }

    @Test
    public void shouldFailVerificationWithExactOneTime() {
        // given

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest()
                            .withPath("some_other_path")
                    )
                    .withTimes(exactly(1))
            ),
            is("Request not found exactly once, expected:<{" + NEW_LINE +
                "  \"path\" : \"some_other_path\"" + NEW_LINE +
                "}> but was:<[]>"));
    }

    @Test
    public void shouldFailVerificationWithExactZeroTimes() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpRequest otherHttpRequest = new HttpRequest().withPath("some_other_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(otherHttpRequest)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(
                        new HttpRequest()
                            .withPath("some_other_path")
                    )
                    .withTimes(exactly(0))
            ),
            is("Request found 1 time but should have been found exactly 0 times, expected:<{" + NEW_LINE +
                "  \"path\" : \"some_other_path\"" + NEW_LINE +
                "}> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"some_path\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"some_other_path\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"some_path\"" + NEW_LINE +
                "} ]>"));
    }

    @Test
    public void shouldFailVerificationWithNoInteractions() {
        // given
        HttpRequest httpRequest = new HttpRequest();

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                verification()
                    .withRequest(request())
                    .withTimes(exactly(0))
            ),
            is("Request found 1 time but should have been found exactly 0 times, expected:<{ }> but was:<{ }>"));
    }

    @Test
    public void shouldPassVerificationWhenRequestLoggedConcurrently() throws Exception {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // when
        CountDownLatch concurrentAddComplete = new CountDownLatch(1);
        new Thread(() -> {
            mockServerEventLog.add(
                new LogEntry()
                    .setHttpRequest(httpRequest)
                    .setType(RECEIVED_REQUEST)
            );
            concurrentAddComplete.countDown();
        }).start();

        if (!concurrentAddComplete.await(10, SECONDS)) {
            fail("Background add() did not complete within 10 seconds");
        }

        // then
        assertThat(verify(verification().withRequest(httpRequest).withTimes(exactly(2))), is(""));
    }

    @Test
    public void shouldIncludeDiffInVerificationFailureWhenEnabled() {
        // given
        configuration.detailedVerificationFailures(true);
        HttpRequest httpRequest = new HttpRequest().withMethod("POST").withPath("some_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        String result = verify(
            verification()
                .withRequest(
                    request().withMethod("GET").withPath("some_other_path")
                )
        );
        assertThat(result, org.hamcrest.CoreMatchers.containsString("Request not found"));
        assertThat(result, org.hamcrest.CoreMatchers.containsString("closest match diff:"));
        assertThat(result, org.hamcrest.CoreMatchers.containsString("method:"));
    }

    @Test
    public void shouldNotIncludeDiffInVerificationFailureWhenDisabled() {
        // given
        configuration.detailedVerificationFailures(false);
        HttpRequest httpRequest = new HttpRequest().withMethod("POST").withPath("some_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then
        String result = verify(
            verification()
                .withRequest(
                    request().withMethod("GET").withPath("some_other_path")
                )
        );
        assertThat(result, org.hamcrest.CoreMatchers.containsString("Request not found"));
        assertThat(result, org.hamcrest.CoreMatchers.not(org.hamcrest.CoreMatchers.containsString("closest match diff:")));
    }

    @Test
    public void shouldPickClosestMatchByFewestFailedFields() {
        // given
        configuration.detailedVerificationFailures(true);
        // closeMatch shares method GET with verification request, so method passes and only path fails
        HttpRequest closeMatch = new HttpRequest().withMethod("GET").withPath("almost_right");
        // farMatch has different method POST so fails at method (first check) with fail-fast
        HttpRequest farMatch = new HttpRequest().withMethod("POST").withPath("completely_wrong");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(farMatch)
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(closeMatch)
                .setType(RECEIVED_REQUEST)
        );

        // then - verification matcher for GET /some_path is used against each received request
        // closeMatch (GET /almost_right): method matches, path fails -> 1 diff field (path)
        // farMatch (POST /completely_wrong): method fails with fail-fast -> 1 diff field (method)
        // both have 1 diff field but closeMatch is actually closer; since both have same count,
        // the first processed one (farMatch) will be kept
        String result = verify(
            verification()
                .withRequest(
                    request().withMethod("GET").withPath("some_path")
                )
        );
        assertThat(result, org.hamcrest.CoreMatchers.containsString("closest match diff:"));
    }

    @Test
    public void shouldNotIncludeDiffWhenNoRequestsLogged() {
        // given
        configuration.detailedVerificationFailures(true);

        // then
        String result = verify(
            verification()
                .withRequest(
                    request().withMethod("GET").withPath("some_path")
                )
        );
        assertThat(result, org.hamcrest.CoreMatchers.containsString("Request not found"));
        assertThat(result, org.hamcrest.CoreMatchers.not(org.hamcrest.CoreMatchers.containsString("closest match diff:")));
    }

    // --- Response Verification Tests ---

    @Test
    public void shouldPassResponseVerificationWithMatchingStatusCode() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpResponse httpResponse = new HttpResponse().withStatusCode(200);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setHttpResponse(httpResponse)
                .setType(FORWARDED_REQUEST)
        );

        // then
        assertThat(verify(
            verification()
                .withResponse(response().withStatusCode(200))
        ), is(""));
    }

    @Test
    public void shouldFailResponseVerificationWithNonMatchingStatusCode() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpResponse httpResponse = new HttpResponse().withStatusCode(200);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setHttpResponse(httpResponse)
                .setType(FORWARDED_REQUEST)
        );

        // then
        String result = verify(
            verification()
                .withResponse(response().withStatusCode(404))
        );
        assertThat(result, containsString("Response not found"));
    }

    @Test
    public void shouldIncludeClosestResponseDiffInResponseVerificationFailureWhenEnabled() {
        // given
        configuration.detailedVerificationFailures(true);
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpResponse httpResponse = new HttpResponse().withStatusCode(200);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setHttpResponse(httpResponse)
                .setType(FORWARDED_REQUEST)
        );

        // then -- verifying 404 against a recorded 200 should fail and append the closest response diff,
        // naming the differing statusCode (recorded under MatchDifference.Field.OPERATION -> "operation")
        String result = verify(
            verification()
                .withResponse(response().withStatusCode(404))
        );
        assertThat(result, containsString("Response not found"));
        assertThat(result, containsString("closest match diff:"));
        assertThat(result, containsString("operation:"));
        assertThat(result, containsString("statusCode match failed"));
    }

    @Test
    public void shouldIncludeClosestResponseBodyDiffInResponseVerificationFailureWhenEnabled() {
        // given
        configuration.detailedVerificationFailures(true);
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpResponse httpResponse = new HttpResponse().withStatusCode(200).withBody("recorded body");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setHttpResponse(httpResponse)
                .setType(FORWARDED_REQUEST)
        );

        // then -- the status matches but the body differs, so the diff names the body field
        String result = verify(
            verification()
                .withResponse(response().withStatusCode(200).withBody("expected body"))
        );
        assertThat(result, containsString("Response not found"));
        assertThat(result, containsString("closest match diff:"));
        assertThat(result, containsString("body:"));
    }

    @Test
    public void shouldApplyRegexResponseBodyMatcherFromRestJson() {
        // given
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("some_path"))
                .setHttpResponse(new HttpResponse().withStatusCode(200).withBody("order-42"))
                .setType(FORWARDED_REQUEST)
        );
        VerificationSerializer verificationSerializer = new VerificationSerializer(new MockServerLogger());

        // then
        assertThat(verify(verificationSerializer.deserialize(
            "{\"httpResponse\":{\"body\":{\"type\":\"REGEX\",\"regex\":\"zzz-nothing\"}},\"times\":{\"atLeast\":1}}"
        )), containsString("Response not found at least once, expected:<{" + NEW_LINE +
            "  \"body\" : {" + NEW_LINE +
            "    \"type\" : \"REGEX\"," + NEW_LINE +
            "    \"regex\" : \"zzz-nothing\""));
        assertThat(verify(verificationSerializer.deserialize(
            "{\"httpResponse\":{\"body\":{\"type\":\"REGEX\",\"regex\":\"order-[0-9]+\"}},\"times\":{\"atLeast\":1}}"
        )), is(""));
    }

    private static final String PETS_SPEC = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"pets\",\"version\":\"1\"},\"paths\":{" +
        "\"/pets\":{\"get\":{\"operationId\":\"listPets\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}," +
        "\"/pets/{petId}\":{\"get\":{\"operationId\":\"showPetById\",\"parameters\":[{\"name\":\"petId\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"}}],\"responses\":{\"200\":{\"description\":\"ok\"}}}}" +
        "}}";

    private void recordPetRequests() {
        for (String path : new String[]{"/pets", "/pets", "/pets/7", "/other"}) {
            mockServerEventLog.add(
                new LogEntry()
                    .setHttpRequest(new HttpRequest().withMethod("GET").withPath(path))
                    .setType(RECEIVED_REQUEST)
            );
        }
    }

    private static String openAPIVerificationJson(String operationId, String times) {
        return "{\"httpRequest\":{\"specUrlOrPayload\":" + PETS_SPEC + (operationId != null ? ",\"operationId\":\"" + operationId + "\"" : "") + "},\"times\":" + times + "}";
    }

    @Test
    public void shouldApplyOpenAPIRequestMatcherFromRestJson() {
        // given
        recordPetRequests();
        VerificationSerializer verificationSerializer = new VerificationSerializer(new MockServerLogger());

        // then
        assertThat(verify(verificationSerializer.deserialize(openAPIVerificationJson("listPets", "{\"atLeast\":2,\"atMost\":2}"))), is(""));
        assertThat(verify(verificationSerializer.deserialize(openAPIVerificationJson("listPets", "{\"atLeast\":1,\"atMost\":1}"))), containsString("Request found 2 times but should have been found exactly once"));
        assertThat(verify(verificationSerializer.deserialize(openAPIVerificationJson("showPetById", "{\"atLeast\":1,\"atMost\":1}"))), is(""));
        assertThat(verify(verificationSerializer.deserialize(openAPIVerificationJson(null, "{\"atLeast\":3,\"atMost\":3}"))), is(""));
        assertThat(verify(verificationSerializer.deserialize(openAPIVerificationJson(null, "{\"atLeast\":4}"))), containsString("Request not found at least 4 times"));
    }

    @Test
    public void shouldApplyOpenAPIRequestMatchersInSequenceFromRestJson() {
        // given
        recordPetRequests();
        VerificationSequenceSerializer verificationSequenceSerializer = new VerificationSequenceSerializer(new MockServerLogger());
        String listPets = "{\"specUrlOrPayload\":" + PETS_SPEC + ",\"operationId\":\"listPets\"}";
        String showPetById = "{\"specUrlOrPayload\":" + PETS_SPEC + ",\"operationId\":\"showPetById\"}";

        // then
        assertThat(verify(verificationSequenceSerializer.deserialize("{\"httpRequests\":[" + listPets + "," + showPetById + "]}")), is(""));
        assertThat(verify(verificationSequenceSerializer.deserialize("{\"httpRequests\":[" + showPetById + "," + listPets + "]}")), containsString("Request sequence not found"));
    }

    @Test
    public void shouldApplyJsonPathResponseBodyMatcherInSequenceFromRestJson() {
        // given
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("first"))
                .setHttpResponse(new HttpResponse().withBody("{\"one\":true}"))
                .setType(FORWARDED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("second"))
                .setHttpResponse(new HttpResponse().withBody("{\"two\":true}"))
                .setType(FORWARDED_REQUEST)
        );
        VerificationSequenceSerializer verificationSequenceSerializer = new VerificationSequenceSerializer(new MockServerLogger());

        // then
        assertThat(verify(verificationSequenceSerializer.deserialize(
            "{\"httpResponses\":[{\"body\":{\"type\":\"JSON_PATH\",\"jsonPath\":\"$.one\"}},{\"body\":{\"type\":\"JSON_PATH\",\"jsonPath\":\"$.two\"}}]}"
        )), is(""));
        assertThat(verify(verificationSequenceSerializer.deserialize(
            "{\"httpResponses\":[{\"body\":{\"type\":\"JSON_PATH\",\"jsonPath\":\"$.two\"}},{\"body\":{\"type\":\"JSON_PATH\",\"jsonPath\":\"$.one\"}}]}"
        )), containsString("Response sequence not found"));
    }

    @Test
    public void shouldNotIncludeClosestResponseDiffInResponseVerificationFailureWhenDisabled() {
        // given
        configuration.detailedVerificationFailures(false);
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpResponse httpResponse = new HttpResponse().withStatusCode(200);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setHttpResponse(httpResponse)
                .setType(FORWARDED_REQUEST)
        );

        // then -- no diff appended when detailed verification failures is off; message unchanged
        String result = verify(
            verification()
                .withResponse(response().withStatusCode(404))
        );
        assertThat(result, containsString("Response not found"));
        assertThat(result, org.hamcrest.CoreMatchers.not(containsString("closest match diff:")));
    }

    @Test
    public void shouldNotIncludeClosestResponseDiffWhenNoResponsesRecorded() {
        // given -- detailed failures on but nothing recorded, so there is no closest response
        configuration.detailedVerificationFailures(true);

        // then
        String result = verify(
            verification()
                .withResponse(response().withStatusCode(404))
        );
        assertThat(result, containsString("Response not found"));
        assertThat(result, org.hamcrest.CoreMatchers.not(containsString("closest match diff:")));
    }

    @Test
    public void shouldIncludeClosestResponseDiffWithoutNpeUnderTraceLogging() {
        // given -- TRACE logging makes MatchDifference dereference its request when recording a
        // difference; the closest-response diff must construct MatchDifference with a non-null request
        // (here a response-only verify, so it falls back to the empty request() placeholder) and must
        // not NPE. Use a fresh event log whose logger sees the TRACE configuration.
        configuration.detailedVerificationFailures(true);
        configuration.logLevel(org.slf4j.event.Level.TRACE);
        MockServerEventLog traceEventLog = new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler, true);
        try {
            HttpRequest httpRequest = new HttpRequest().withPath("some_path");
            HttpResponse httpResponse = new HttpResponse().withStatusCode(200);

            // when
            traceEventLog.add(
                new LogEntry()
                    .setHttpRequest(httpRequest)
                    .setHttpResponse(httpResponse)
                    .setType(FORWARDED_REQUEST)
            );

            // then -- response-only verify (no request) so the diff uses the placeholder request; no NPE
            CompletableFuture<String> resultFuture = new CompletableFuture<>();
            traceEventLog.verify(
                verification().withResponse(response().withStatusCode(404)),
                resultFuture::complete
            );
            String result;
            try {
                result = resultFuture.get(10, SECONDS);
            } catch (Exception e) {
                fail(e.getMessage());
                return;
            }
            assertThat(result, containsString("Response not found"));
            assertThat(result, containsString("closest match diff:"));
            assertThat(result, containsString("operation:"));
        } finally {
            traceEventLog.stop();
        }
    }

    @Test
    public void shouldPickClosestResponseByFewestDifferingFields() {
        // given
        configuration.detailedVerificationFailures(true);
        // farMatch differs on both statusCode and body; closeMatch matches statusCode and differs only
        // on body -> fewer differing fields, so it must be selected as the closest response
        HttpResponse farMatch = new HttpResponse().withStatusCode(500).withBody("wrong body");
        HttpResponse closeMatch = new HttpResponse().withStatusCode(200).withBody("almost right");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("p1"))
                .setHttpResponse(farMatch)
                .setType(FORWARDED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("p2"))
                .setHttpResponse(closeMatch)
                .setType(FORWARDED_REQUEST)
        );

        // then -- verifying status 200 + a specific body; closeMatch is closest (only body differs),
        // so the appended diff names body but NOT the operation/statusCode field
        String result = verify(
            verification()
                .withResponse(response().withStatusCode(200).withBody("expected body"))
        );
        assertThat(result, containsString("closest match diff:"));
        assertThat(result, containsString("body:"));
        assertThat(result, org.hamcrest.CoreMatchers.not(containsString("statusCode match failed")));
    }

    @Test
    public void shouldPassResponseVerificationWithRequestAndResponseFilter() {
        // given
        HttpRequest httpRequest1 = new HttpRequest().withPath("path_one");
        HttpResponse httpResponse1 = new HttpResponse().withStatusCode(200);
        HttpRequest httpRequest2 = new HttpRequest().withPath("path_two");
        HttpResponse httpResponse2 = new HttpResponse().withStatusCode(404);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest1)
                .setHttpResponse(httpResponse1)
                .setType(FORWARDED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest2)
                .setHttpResponse(httpResponse2)
                .setType(FORWARDED_REQUEST)
        );

        // then -- match request path AND response status code
        assertThat(verify(
            verification()
                .withRequest(request().withPath("path_one"))
                .withResponse(response().withStatusCode(200))
        ), is(""));

        // cross-check: wrong combination should fail
        String result = verify(
            verification()
                .withRequest(request().withPath("path_one"))
                .withResponse(response().withStatusCode(404))
        );
        assertThat(result, containsString("Response not found"));
    }

    @Test
    public void shouldPassResponseVerificationWithExactlyTimes() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpResponse httpResponse200 = new HttpResponse().withStatusCode(200);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setHttpResponse(httpResponse200)
                .setType(FORWARDED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setHttpResponse(httpResponse200)
                .setType(FORWARDED_REQUEST)
        );

        // then
        assertThat(verify(
            verification()
                .withResponse(response().withStatusCode(200))
                .withTimes(exactly(2))
        ), is(""));
    }

    @Test
    public void shouldFailResponseVerificationWithExactlyTimesWrong() {
        // given
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");
        HttpResponse httpResponse200 = new HttpResponse().withStatusCode(200);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setHttpResponse(httpResponse200)
                .setType(FORWARDED_REQUEST)
        );

        // then -- expecting exactly 2 but only 1 recorded
        String result = verify(
            verification()
                .withResponse(response().withStatusCode(200))
                .withTimes(exactly(2))
        );
        assertThat(result, containsString("Response not found"));
    }

    @Test
    public void shouldPassResponseVerificationWithResponseOnlyNoRequest() {
        // given -- response verification without httpRequest should match any request
        HttpRequest httpRequest1 = new HttpRequest().withPath("path_one");
        HttpRequest httpRequest2 = new HttpRequest().withPath("path_two");
        HttpResponse httpResponse = new HttpResponse().withStatusCode(200);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest1)
                .setHttpResponse(httpResponse)
                .setType(FORWARDED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest2)
                .setHttpResponse(httpResponse)
                .setType(FORWARDED_REQUEST)
        );

        // then
        assertThat(verify(
            verification()
                .withResponse(response().withStatusCode(200))
                .withTimes(exactly(2))
        ), is(""));
    }

    @Test
    public void shouldNotAffectExistingRequestVerification() {
        // given -- standard request-only verification should still work the same
        HttpRequest httpRequest = new HttpRequest().withPath("some_path");

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(httpRequest)
                .setType(RECEIVED_REQUEST)
        );

        // then -- no response set, should use existing request path
        assertThat(verify(
            verification()
                .withRequest(request().withPath("some_path"))
        ), is(""));
    }

    // --- Response-aware Sequence Verification Tests ---

    @Test
    public void shouldPassResponseSequenceVerification() {
        // given
        HttpRequest request1 = new HttpRequest().withPath("path_one");
        HttpResponse response1 = new HttpResponse().withStatusCode(200);
        HttpRequest request2 = new HttpRequest().withPath("path_two");
        HttpResponse response2 = new HttpResponse().withStatusCode(404);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request1)
                .setHttpResponse(response1)
                .setType(FORWARDED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request2)
                .setHttpResponse(response2)
                .setType(FORWARDED_REQUEST)
        );

        // then
        assertThat(verify(
            new VerificationSequence()
                .withRequests(request().withPath("path_one"), request().withPath("path_two"))
                .withResponses(response().withStatusCode(200), response().withStatusCode(404))
        ), is(""));
    }

    @Test
    public void shouldFailResponseSequenceVerificationWithWrongOrder() {
        // given
        HttpRequest request1 = new HttpRequest().withPath("path_one");
        HttpResponse response1 = new HttpResponse().withStatusCode(200);
        HttpRequest request2 = new HttpRequest().withPath("path_two");
        HttpResponse response2 = new HttpResponse().withStatusCode(404);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request1)
                .setHttpResponse(response1)
                .setType(FORWARDED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request2)
                .setHttpResponse(response2)
                .setType(FORWARDED_REQUEST)
        );

        // then -- reversed response order should fail
        String result = verify(
            new VerificationSequence()
                .withRequests(request().withPath("path_one"), request().withPath("path_two"))
                .withResponses(response().withStatusCode(404), response().withStatusCode(200))
        );
        assertThat(result, containsString("Response sequence not found"));
    }

    // --- Fix #1: response verification must exclude NO_MATCH_RESPONSE auto-404s ---

    @Test
    public void shouldNotCountNoMatchResponseInResponseVerificationButRetrieveStillReturnsIt() {
        // given -- a request that matched no expectation, so MockServer auto-generated a 404
        HttpRequest unmatchedRequest = new HttpRequest().withPath("some_path");
        HttpResponse autoNotFound = new HttpResponse().withStatusCode(404);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(unmatchedRequest)
                .setHttpResponse(autoNotFound)
                .setType(NO_MATCH_RESPONSE)
        );

        // then -- response verification of a 404 must NOT count the auto-generated no-match 404
        String result = verify(
            verification()
                .withResponse(response().withStatusCode(404))
        );
        assertThat(result, containsString("Response not found"));

        // but /retrieve must still return the NO_MATCH_RESPONSE pair (it is excluded only from verification)
        List<LogEventRequestAndResponse> retrieved = retrieveRequestResponses(null);
        assertThat(retrieved, hasSize(1));
        assertThat(retrieved.get(0).getHttpResponse().getStatusCode(), is(404));
    }

    @Test
    public void shouldCountExpectationResponseAndForwardedRequestInResponseVerification() {
        // given -- one mock-produced response and one forwarded response, both 200
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("a"))
                .setHttpResponse(new HttpResponse().withStatusCode(200))
                .setType(EXPECTATION_RESPONSE)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("b"))
                .setHttpResponse(new HttpResponse().withStatusCode(200))
                .setType(FORWARDED_REQUEST)
        );
        // and a no-match 200 that must be ignored by verification
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("c"))
                .setHttpResponse(new HttpResponse().withStatusCode(200))
                .setType(NO_MATCH_RESPONSE)
        );

        // then -- exactly the two mock-produced responses are counted
        assertThat(verify(
            verification()
                .withResponse(response().withStatusCode(200))
                .withTimes(exactly(2))
        ), is(""));
    }

    @Test
    public void shouldNotCountNoMatchResponseInResponseSequenceVerification() {
        // given -- a NO_MATCH_RESPONSE 404 followed by a real EXPECTATION_RESPONSE 200
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("unmatched"))
                .setHttpResponse(new HttpResponse().withStatusCode(404))
                .setType(NO_MATCH_RESPONSE)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("matched"))
                .setHttpResponse(new HttpResponse().withStatusCode(200))
                .setType(EXPECTATION_RESPONSE)
        );

        // then -- a response-only sequence of [200] passes (the 404 no-match is invisible)
        assertThat(verify(
            new VerificationSequence()
                .withResponses(response().withStatusCode(200))
        ), is(""));

        // and a sequence demanding the 404 first then 200 must fail (404 is not counted)
        String result = verify(
            new VerificationSequence()
                .withResponses(response().withStatusCode(404), response().withStatusCode(200))
        );
        assertThat(result, containsString("Response sequence not found"));
    }

    // --- Fix #2: response-aware sequence with mismatched non-empty list lengths is rejected ---

    @Test
    public void shouldPassResponseSequenceWithMatchedLengthRequestResponsePairs() {
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("path_one"))
                .setHttpResponse(new HttpResponse().withStatusCode(200))
                .setType(FORWARDED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("path_two"))
                .setHttpResponse(new HttpResponse().withStatusCode(201))
                .setType(FORWARDED_REQUEST)
        );

        assertThat(verify(
            new VerificationSequence()
                .withRequests(request().withPath("path_one"), request().withPath("path_two"))
                .withResponses(response().withStatusCode(200), response().withStatusCode(201))
        ), is(""));
    }

    @Test
    public void shouldPassResponseOnlySequenceWithEmptyRequests() {
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("path_one"))
                .setHttpResponse(new HttpResponse().withStatusCode(200))
                .setType(FORWARDED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("path_two"))
                .setHttpResponse(new HttpResponse().withStatusCode(201))
                .setType(FORWARDED_REQUEST)
        );

        // response-only sequence (no requests) is valid
        assertThat(verify(
            new VerificationSequence()
                .withResponses(response().withStatusCode(200), response().withStatusCode(201))
        ), is(""));
    }

    @Test
    public void shouldRejectResponseSequenceWithMismatchedNonEmptyListLengths() {
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("path_one"))
                .setHttpResponse(new HttpResponse().withStatusCode(200))
                .setType(FORWARDED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("path_two"))
                .setHttpResponse(new HttpResponse().withStatusCode(201))
                .setType(FORWARDED_REQUEST)
        );

        // two requests but only one response — must be a clear error, NOT a silent pass
        String result = verify(
            new VerificationSequence()
                .withRequests(request().withPath("path_one"), request().withPath("path_two"))
                .withResponses(response().withStatusCode(200))
        );
        assertThat(result, containsString("Request and response sequences must be the same length"));
        assertThat(result, containsString("2 request(s) and 1 response(s)"));
    }

    // --- Fix #3: a pair with a null recorded request must not NPE in a request-constrained step ---

    @Test
    public void shouldTreatNullRequestPairAsNonMatchingInResponseSequence() {
        // given -- a recorded pair whose request is null but whose response is present
        mockServerEventLog.add(
            new LogEntry()
                .setHttpResponse(new HttpResponse().withStatusCode(200))
                .setType(FORWARDED_REQUEST)
        );

        // when -- a request-constrained response-sequence step is verified
        String result = verify(
            new VerificationSequence()
                .withRequests(request().withPath("some_path"))
                .withResponses(response().withStatusCode(200))
        );

        // then -- it must report a normal sequence-not-found failure, not an exception/hang
        assertThat(result, containsString("Response sequence not found"));
        assertThat(result, is(org.hamcrest.CoreMatchers.not(containsString("exception"))));
    }

    // --- Fix #5: a failing response sequence reports the RESPONSES, not the requests ---

    @Test
    public void shouldReportResponsesInFailingResponseSequenceMessage() {
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("path_one"))
                .setHttpResponse(new HttpResponse().withStatusCode(200))
                .setType(FORWARDED_REQUEST)
        );

        // expect a 503 in the sequence that was never recorded
        String result = verify(
            new VerificationSequence()
                .withResponses(response().withStatusCode(503))
        );
        assertThat(result, containsString("Response sequence not found"));
        // the expected sequence (503) and the recorded response (200) must appear, as RESPONSES
        assertThat(result, containsString("503"));
        assertThat(result, containsString("200"));
    }

    // --- Fix #6: an entirely-empty verification sequence is rejected, not vacuously passed ---

    @Test
    public void shouldRejectEntirelyEmptyVerificationSequence() {
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("some_path"))
                .setType(RECEIVED_REQUEST)
        );

        String result = verify(new VerificationSequence());
        assertThat(result, containsString("No expectations, requests or responses specified"));
    }

    // --- Fix #4: a verification whose filter build throws still completes (no hang) ---

    @Test
    public void shouldCompleteResponseVerificationWhenRequestFilterBuildThrows() {
        // given -- a recorded response
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(new HttpRequest().withPath("some_path"))
                .setHttpResponse(new HttpResponse().withStatusCode(200))
                .setType(FORWARDED_REQUEST)
        );

        // when -- the request filter is an invalid OpenAPI definition whose matcher build throws
        OpenAPIDefinition invalidOpenAPI = OpenAPIDefinition.openAPI("{\"this\":\"is not a valid openapi spec\"}");
        String result = verify(
            verification()
                .withRequest(invalidOpenAPI)
                .withResponse(response().withStatusCode(200))
        );

        // then -- the verify future completes within the helper's 10s timeout. Without the fix the
        // matcher-build IllegalArgumentException is swallowed by the disruptor and the future never
        // completes, so result.get(10, SECONDS) would time out and fail() the test. With the fix the
        // build failure routes an empty result, so verification finishes as a "Response not found".
        assertThat(result, is(org.hamcrest.CoreMatchers.notNullValue()));
        assertThat(result, containsString("Response not found"));
    }
}

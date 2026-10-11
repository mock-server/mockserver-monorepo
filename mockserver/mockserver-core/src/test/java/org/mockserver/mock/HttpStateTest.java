package org.mockserver.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.InjectMocks;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FilePath;
import org.mockserver.file.FileReader;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.time.EpochService;
import org.mockserver.time.GlobalFixedTime;
import org.mockserver.time.TimeService;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.BinaryRequestDefinition;
import org.mockserver.model.BinaryResponse;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.RetrieveType;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.*;
import org.mockserver.serialization.java.ExpectationToJavaSerializer;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.slf4j.event.Level;

import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.fail;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LOG_DATE_FORMAT;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.log.model.LogEntryMessages.RECEIVED_REQUEST_MESSAGE_FORMAT;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.mock.OpenAPIExpectation.openAPIExpectation;
import static org.mockserver.model.ExpectationId.expectationId;
import static org.mockserver.model.Format.LOG_ENTRIES;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.PortBinding.portBinding;
import static org.mockserver.model.RetrieveType.REQUEST_RESPONSES;
import static org.slf4j.event.Level.INFO;

/**
 * @author jamesdbloom
 */
public class HttpStateTest {

    @Rule
    public final ExpectedException exception = ExpectedException.none();
    private final RequestDefinitionSerializer requestDefinitionSerializer = new RequestDefinitionSerializer(new MockServerLogger());
    private final ExpectationIdSerializer expectationIdSerializer = new ExpectationIdSerializer(new MockServerLogger());
    private final ExpectationSerializer expectationSerializer = new ExpectationSerializer(new MockServerLogger());
    private final ExpectationSerializer expectationSerializerWithDefaultFields = new ExpectationSerializer(new MockServerLogger(), true);
    private final OpenAPIExpectationSerializer openAPIExpectationSerializer = new OpenAPIExpectationSerializer(new MockServerLogger());
    private final ExpectationToJavaSerializer expectationToJavaSerializer = new ExpectationToJavaSerializer();
    private final PortBindingSerializer portBindingSerializer = new PortBindingSerializer(new MockServerLogger());
    private final VerificationSerializer verificationSerializer = new VerificationSerializer(new MockServerLogger());
    private final VerificationSequenceSerializer verificationSequenceSerializer = new VerificationSequenceSerializer(new MockServerLogger());

    private final Configuration configuration = configuration();
    @InjectMocks
    private HttpState httpState;

    @ClassRule
    public static final GlobalFixedTime fixedTime = new GlobalFixedTime();

    private java.util.concurrent.ScheduledExecutorService schedulerExecutor;

    @Before
    public void prepareTestFixture() {
        configuration.detailedVerificationFailures(false);
        Scheduler scheduler = mock(Scheduler.class);
        // Several control-plane handlers (e.g. contract-test) deliberately offload their blocking
        // work off the Netty event loop onto the scheduler's executor; back the mock with a real
        // executor so that offloaded work actually runs.
        schedulerExecutor = java.util.concurrent.Executors.newScheduledThreadPool(2);
        org.mockito.Mockito.when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
        openMocks(this);
    }

    @After
    public void resetClock() {
        if (httpState != null) {
            httpState.stop();
        }
        TimeService.reset();
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
        }
    }

    private static class FakeResponseWriter extends ResponseWriter {
        public volatile HttpResponse response;
        private final java.util.concurrent.CountDownLatch responseLatch = new java.util.concurrent.CountDownLatch(1);

        protected FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
            responseLatch.countDown();
        }

        /**
         * Block until {@link #sendResponse} has been called (the response may be produced on a
         * different thread when the handler offloads its work). Fails the test on timeout.
         */
        public void awaitResponse() throws InterruptedException {
            if (!responseLatch.await(30, SECONDS)) {
                fail("timed out waiting for the handler to write a response");
            }
        }
    }

    @Test
    public void shouldHandleRetrieveRequestsRequest() {
        // given
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest expectationRetrieveRequestsRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withBody(
                requestDefinitionSerializer.serialize(request("request_one"))
            );
        boolean handle = httpState.handle(expectationRetrieveRequestsRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), is(requestDefinitionSerializer.serialize(true, Collections.singletonList(
            request("request_one")
        ))));
    }

    @Test
    public void shouldHandleBaselineCompareRequestWithExplicitCurrent() throws Exception {
        // given — baseline has one interaction, current adds a field to the response body
        String baseline = expectationSerializer.serialize(Collections.singletonList(
            new Expectation(request().withMethod("GET").withPath("/api/users"))
                .thenRespond(response().withStatusCode(200).withBody("{\"id\":1}"))));
        String current = expectationSerializer.serialize(Collections.singletonList(
            new Expectation(request().withMethod("GET").withPath("/api/users"))
                .thenRespond(response().withStatusCode(200).withBody("{\"id\":1,\"email\":\"a@b.com\"}"))));
        HttpRequest compareRequest = request("/mockserver/baseline/compare")
            .withMethod("PUT")
            .withBody("{\"baseline\":" + baseline + ",\"current\":" + current + "}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(compareRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        com.fasterxml.jackson.databind.JsonNode report =
            org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                .readTree(responseWriter.response.getBodyAsString());
        assertThat(report.get("added").size(), is(0));
        assertThat(report.get("removed").size(), is(0));
        assertThat(report.get("changed").size(), is(1));
        assertThat(report.get("changed").get(0).get("key").asText(), is("GET /api/users"));
        assertThat(report.get("changed").get(0).get("responseDiffs").get(0).get("field").asText(),
            is("response.body.email"));
    }

    @Test
    public void shouldHandleBaselineCompareRequestAgainstLiveRecordedExpectations() throws Exception {
        // given — live recorded expectation matches the baseline exactly (no current supplied)
        httpState.add(new Expectation(request().withMethod("GET").withPath("/api/users"))
            .thenRespond(response().withStatusCode(200).withBody("{\"id\":1}")));
        String baseline = expectationSerializer.serialize(Collections.singletonList(
            new Expectation(request().withMethod("GET").withPath("/api/users"))
                .thenRespond(response().withStatusCode(200).withBody("{\"id\":1}"))));
        HttpRequest compareRequest = request("/mockserver/baseline/compare")
            .withMethod("PUT")
            .withBody("{\"baseline\":" + baseline + "}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(compareRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        com.fasterxml.jackson.databind.JsonNode report =
            org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                .readTree(responseWriter.response.getBodyAsString());
        assertThat(report.get("hasDrift").asBoolean(), is(false));
        assertThat(report.get("added").size(), is(0));
        assertThat(report.get("removed").size(), is(0));
        assertThat(report.get("changed").size(), is(0));
    }

    @Test
    public void shouldHandleContractTestRequestWhenServiceConforms() throws Exception {
        // given — a conformant SUT: listPets (GET /pets) returns a JSON array as the spec requires
        String spec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
        httpState.setReplayHandler(req -> {
            HttpResponse upstream = response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("[{\"id\":1,\"name\":\"Fido\"}]");
            return CompletableFuture.completedFuture(upstream);
        });
        HttpRequest contractTestRequest = request("/mockserver/contractTest")
            .withMethod("PUT")
            .withBody("{\"spec\":" + org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().writeValueAsString(spec)
                + ",\"baseUrl\":\"http://localhost:1080\",\"operationId\":\"listPets\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(contractTestRequest, responseWriter, false);
        responseWriter.awaitResponse();

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        com.fasterxml.jackson.databind.JsonNode report =
            org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                .readTree(responseWriter.response.getBodyAsString());
        assertThat(report.get("baseUrl").asText(), is("http://localhost:1080"));
        assertThat(report.get("totalOperations").asInt(), is(1));
        assertThat(report.get("passed").asInt(), is(1));
        assertThat(report.get("failed").asInt(), is(0));
        assertThat(report.get("allPassed").asBoolean(), is(true));
        com.fasterxml.jackson.databind.JsonNode result = report.get("results").get(0);
        assertThat(result.get("operationId").asText(), is("listPets"));
        assertThat(result.get("passed").asBoolean(), is(true));
        assertThat(result.get("validationErrors").size(), is(0));
    }

    @Test
    public void shouldNotReportContractTestSuccessWhenNoOperationRan() throws Exception {
        // given — an operationId filter that matches nothing in the spec. "allPassed" used to be
        // computed as "passed == results.size()", which is vacuously true for an empty result set,
        // so a mistyped or stale operationId reported a green contract test that verified nothing.
        String spec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
        httpState.setReplayHandler(req -> {
            throw new AssertionError("no operation matched the filter, so no request should be sent");
        });
        HttpRequest contractTestRequest = request("/mockserver/contractTest")
            .withMethod("PUT")
            .withBody("{\"spec\":" + org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().writeValueAsString(spec)
                + ",\"baseUrl\":\"http://localhost:1080\",\"operationId\":\"noSuchOperation\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(contractTestRequest, responseWriter, false);
        responseWriter.awaitResponse();

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        com.fasterxml.jackson.databind.JsonNode report =
            org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                .readTree(responseWriter.response.getBodyAsString());
        assertThat(report.get("totalOperations").asInt(), is(0));
        assertThat("a run that exercised no operation has verified nothing and must not report success",
            report.get("allPassed").asBoolean(), is(false));
        assertThat(report.get("error").asText(), containsString("noSuchOperation"));
    }

    @Test
    public void shouldHandleContractTestRequestWhenServiceViolatesSpec() throws Exception {
        // given — a non-conformant SUT: listPets returns a JSON object instead of an array
        String spec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
        httpState.setReplayHandler(req -> {
            HttpResponse upstream = response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"not\":\"an array\"}");
            return CompletableFuture.completedFuture(upstream);
        });
        HttpRequest contractTestRequest = request("/mockserver/contractTest")
            .withMethod("PUT")
            .withBody("{\"spec\":" + org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().writeValueAsString(spec)
                + ",\"baseUrl\":\"http://localhost:1080\",\"operationId\":\"listPets\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(contractTestRequest, responseWriter, false);
        responseWriter.awaitResponse();

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        com.fasterxml.jackson.databind.JsonNode report =
            org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                .readTree(responseWriter.response.getBodyAsString());
        assertThat(report.get("totalOperations").asInt(), is(1));
        assertThat(report.get("passed").asInt(), is(0));
        assertThat(report.get("failed").asInt(), is(1));
        assertThat(report.get("allPassed").asBoolean(), is(false));
        com.fasterxml.jackson.databind.JsonNode result = report.get("results").get(0);
        assertThat(result.get("operationId").asText(), is("listPets"));
        assertThat(result.get("passed").asBoolean(), is(false));
        assertThat(result.get("validationErrors").size(), is(greaterThan(0)));
    }

    @Test
    public void shouldRunContractTestOffTheCallingThreadWithAsynchronousReplayHandler() throws Exception {
        // given — a replay handler whose future is completed from a SEPARATE thread after a short
        // delay (NOT CompletableFuture.completedFuture). This is what a real wired NettyHttpClient
        // does, and it is the case that would self-deadlock if the per-operation .get(timeout) ran
        // on the Netty event-loop thread. The contract-test handler MUST offload that blocking
        // .get() onto the scheduler executor, so the calling thread must NOT block.
        String spec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
        java.util.concurrent.ExecutorService asyncCompleter = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final Thread callingThread = Thread.currentThread();
            final java.util.concurrent.atomic.AtomicReference<Thread> senderThread = new java.util.concurrent.atomic.AtomicReference<>();
            httpState.setReplayHandler(req -> {
                senderThread.set(Thread.currentThread());
                CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                asyncCompleter.submit(() -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    future.complete(response()
                        .withStatusCode(200)
                        .withHeader("content-type", "application/json")
                        .withBody("[{\"id\":1,\"name\":\"Fido\"}]"));
                });
                return future;
            });
            HttpRequest contractTestRequest = request("/mockserver/contractTest")
                .withMethod("PUT")
                .withBody("{\"spec\":" + org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().writeValueAsString(spec)
                    + ",\"baseUrl\":\"http://localhost:1080\",\"operationId\":\"listPets\"}");
            FakeResponseWriter responseWriter = new FakeResponseWriter();
            CompletableFuture<Boolean> canHandle = new CompletableFuture<>();

            // when — invoke the handler directly so we can observe that the calling thread returns
            // before the (asynchronously completed) work has produced a response.
            httpState.handleContractTestForTest(contractTestRequest, responseWriter, canHandle);

            // then — the handler returned without blocking; the response is produced later, on the
            // off-loop worker, and the per-operation .get() did NOT run on the calling thread.
            assertThat("handler must offload and return before the async future completes",
                responseWriter.response, is(nullValue()));
            assertThat("the calling thread must not be held waiting for the offloaded run",
                canHandle.getNow(false), is(true));
            responseWriter.awaitResponse();
            assertThat(canHandle.get(30, SECONDS), is(true));
            assertThat(senderThread.get(), is(notNullValue()));
            assertThat("the blocking per-operation .get() must not run on the calling (event-loop) thread",
                senderThread.get(), is(not(callingThread)));

            assertThat(responseWriter.response.getStatusCode(), is(200));
            com.fasterxml.jackson.databind.JsonNode report =
                org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                    .readTree(responseWriter.response.getBodyAsString());
            assertThat(report.get("totalOperations").asInt(), is(1));
            assertThat(report.get("passed").asInt(), is(1));
            assertThat(report.get("allPassed").asBoolean(), is(true));
        } finally {
            asyncCompleter.shutdownNow();
        }
    }

    @Test
    public void shouldHonourPerOperationTimeoutWhenReplayFutureNeverCompletes() throws Exception {
        // given — a replay handler that returns a future that NEVER completes. The per-operation
        // .get(maxSocketTimeout) must bound the wait so a single hung upstream operation cannot
        // hang the run forever — and crucially it must do so off the event loop.
        configuration.maxSocketTimeoutInMillis(200L);
        String spec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
        httpState.setReplayHandler(req -> new CompletableFuture<>()); // never completes
        HttpRequest contractTestRequest = request("/mockserver/contractTest")
            .withMethod("PUT")
            .withBody("{\"spec\":" + org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().writeValueAsString(spec)
                + ",\"baseUrl\":\"http://localhost:1080\",\"operationId\":\"listPets\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        CompletableFuture<Boolean> canHandle = new CompletableFuture<>();

        // when
        long start = System.currentTimeMillis();
        httpState.handleContractTestForTest(contractTestRequest, responseWriter, canHandle);
        responseWriter.awaitResponse();
        long elapsed = System.currentTimeMillis() - start;

        // then — the per-operation timeout was honoured (failed, not hung), and it completed well
        // within the response-await window rather than hanging.
        assertThat(canHandle.get(30, SECONDS), is(true));
        assertThat("the never-completing operation must time out, not hang", elapsed, is(lessThan(20_000L)));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        com.fasterxml.jackson.databind.JsonNode report =
            org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                .readTree(responseWriter.response.getBodyAsString());
        assertThat(report.get("totalOperations").asInt(), is(1));
        assertThat(report.get("failed").asInt(), is(1));
        assertThat(report.get("allPassed").asBoolean(), is(false));
    }

    @Test
    public void shouldRejectContractTestRequestWithoutBaseUrl() {
        // given — body missing the required baseUrl
        httpState.setReplayHandler(req -> CompletableFuture.completedFuture(response().withStatusCode(200)));
        HttpRequest contractTestRequest = request("/mockserver/contractTest")
            .withMethod("PUT")
            .withBody("{\"spec\":\"{}\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(contractTestRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("baseUrl"));
    }

    @Test
    public void shouldHandleTrafficValidateRequestWhenRecordedTrafficConformsToSpec() throws Exception {
        // given — a recorded request/response pair whose response conforms to the GET /pets schema
        String spec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
        httpState.log(
            new LogEntry()
                .setType(EXPECTATION_RESPONSE)
                .setHttpRequest(request("/pets").withMethod("GET"))
                .setHttpResponse(response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody("[{\"id\":1,\"name\":\"Fido\"}]"))
        );
        HttpRequest trafficValidateRequest = request("/mockserver/trafficValidate")
            .withMethod("PUT")
            .withBody("{\"spec\":" + org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().writeValueAsString(spec) + "}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when — handler offloads onto the scheduler executor, so await the async response
        boolean handle = httpState.handle(trafficValidateRequest, responseWriter, false);
        responseWriter.awaitResponse();

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        com.fasterxml.jackson.databind.JsonNode report =
            org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                .readTree(responseWriter.response.getBodyAsString());
        assertThat(report.get("totalRequests").asInt(), is(1));
        assertThat(report.get("passed").asInt(), is(1));
        assertThat(report.get("failed").asInt(), is(0));
        assertThat(report.get("allPassed").asBoolean(), is(true));
        com.fasterxml.jackson.databind.JsonNode result = report.get("results").get(0);
        assertThat(result.get("method").asText(), is("GET"));
        assertThat(result.get("path").asText(), is("/pets"));
        assertThat(result.get("matchedOperation").asText(), is(notNullValue()));
        assertThat(result.get("passed").asBoolean(), is(true));
        assertThat(result.get("responseErrors").size(), is(0));
    }

    @Test
    public void shouldHandleTrafficValidateRequestWhenRecordedTrafficViolatesSpec() throws Exception {
        // given — a recorded response that VIOLATES the GET /pets schema (object, not array)
        String spec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
        httpState.log(
            new LogEntry()
                .setType(EXPECTATION_RESPONSE)
                .setHttpRequest(request("/pets").withMethod("GET"))
                .setHttpResponse(response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody("{\"not\":\"an array\"}"))
        );
        HttpRequest trafficValidateRequest = request("/mockserver/trafficValidate")
            .withMethod("PUT")
            .withBody("{\"spec\":" + org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().writeValueAsString(spec) + "}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(trafficValidateRequest, responseWriter, false);
        responseWriter.awaitResponse();

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        com.fasterxml.jackson.databind.JsonNode report =
            org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                .readTree(responseWriter.response.getBodyAsString());
        assertThat(report.get("totalRequests").asInt(), is(1));
        assertThat(report.get("passed").asInt(), is(0));
        assertThat(report.get("failed").asInt(), is(1));
        assertThat(report.get("allPassed").asBoolean(), is(false));
        com.fasterxml.jackson.databind.JsonNode result = report.get("results").get(0);
        assertThat(result.get("passed").asBoolean(), is(false));
        assertThat(result.get("responseErrors").size(), is(greaterThan(0)));
    }

    @Test
    public void shouldRejectTrafficValidateRequestWithoutSpec() {
        // given — body missing the required spec
        HttpRequest trafficValidateRequest = request("/mockserver/trafficValidate")
            .withMethod("PUT")
            .withBody("{}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(trafficValidateRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("spec"));
    }

    @Test
    public void shouldBlockTrafficValidateSpecUrlBySsrfPolicy() {
        // given — SSRF protection enabled (forwardProxyBlockPrivateNetworks=true), and a spec
        // referenced by an http URL whose host is the cloud-metadata endpoint, which must be rejected
        // before the OpenAPI parser is allowed to dereference it.
        Configuration ssrfConfiguration = configuration().forwardProxyBlockPrivateNetworks(true);
        Scheduler scheduler = mock(Scheduler.class);
        org.mockito.Mockito.when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        HttpState ssrfState = new HttpState(ssrfConfiguration, new MockServerLogger(ssrfConfiguration, MockServerLogger.class), scheduler);
        try {
            HttpRequest trafficValidateRequest = request("/mockserver/trafficValidate")
                .withMethod("PUT")
                .withBody("{\"spec\":\"http://169.254.169.254/openapi.json\"}");
            FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            boolean handle = ssrfState.handle(trafficValidateRequest, responseWriter, false);

            // then
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(403));
            assertThat(responseWriter.response.getBodyAsString(), containsString("SSRF"));
        } finally {
            ssrfState.stop();
        }
    }

    @Test
    public void shouldHandleClearRequest() {
        // given
        httpState.add(new Expectation(request("request_one")).thenRespond(response("response_one")));
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(EXPECTATION_MATCHED)
        );
        HttpRequest clearRequest = request("/mockserver/clear")
            .withMethod("PUT")
            .withBody(
                requestDefinitionSerializer.serialize(request("request_one"))
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(clearRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), is(""));
        assertThat(httpState.firstMatchingExpectation(request("request_one")), is(nullValue()));
        assertThat(httpState.retrieve(request("/mockserver/retrieve")
            .withMethod("PUT")
            .withBody(
                requestDefinitionSerializer.serialize(request("request_one"))
            )), is(response().withBody("[]", MediaType.JSON_UTF_8).withStatusCode(200)));
    }

    @Test
    public void shouldHandleReturnStatusRequest() {
        // given
        HttpRequest statusRequest = request("/mockserver/status").withMethod("PUT");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(statusRequest, responseWriter, false);

        // then
        assertThat(handle, is(false));
        assertThat(responseWriter.response, is(nullValue()));
    }

    @Test
    public void shouldHandleBindNewPortsRequest() {
        // given
        HttpRequest statusRequest = request("/mockserver/bind")
            .withMethod("PUT")
            .withBody(portBindingSerializer.serialize(
                portBinding(1090, 1090)
            ));
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(statusRequest, responseWriter, false);

        // then
        assertThat(handle, is(false));
        assertThat(responseWriter.response, is(nullValue()));
    }

    @Test
    public void shouldHandleStopRequest() {
        // given
        HttpRequest statusRequest = request("/mockserver/stop")
            .withMethod("PUT");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(statusRequest, responseWriter, false);

        // then
        assertThat(handle, is(false));
        assertThat(responseWriter.response, is(nullValue()));
    }

    @Test
    public void shouldHandleRetrieveRecordedExpectationsRequest() {
        // given
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setExpectation(new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("response_one")))
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest expectationRetrieveExpectationsRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.RECORDED_EXPECTATIONS.name())
            .withBody(
                requestDefinitionSerializer.serialize(request("request_one"))
            );
        boolean handle = httpState.handle(expectationRetrieveExpectationsRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), is(expectationSerializerWithDefaultFields.serialize(Collections.singletonList(
            new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("response_one"))
        ))));
    }

    @Test
    public void shouldRetrieveRecordedExpectationsAsJavaWithoutIdsWhateverRanBefore() {
        // given
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/recorded_one"))
                .setHttpResponse(response("response_one"))
                .setExpectation(new Expectation(request("/recorded_one"), Times.once(), TimeToLive.unlimited(), 0).thenRespond(response("response_one")))
        );
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/recorded_two"))
                .setHttpResponse(response("response_two"))
                .setExpectation(new Expectation(request("/recorded_two"), Times.once(), TimeToLive.unlimited(), 0).withId("key_two").thenRespond(response("response_two")))
        );

        // when
        FakeResponseWriter javaBefore = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS, "JAVA", request()), javaBefore, false), is(true));
        FakeResponseWriter json = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS, "JSON", request()), json, false), is(true));
        FakeResponseWriter javaAfter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS, "JAVA", request()), javaAfter, false), is(true));

        // then
        assertThat(json.response.getBodyAsString(), containsString("\"id\""));
        String generated = javaBefore.response.getBodyAsString();
        assertThat(generated, containsString("/recorded_one"));
        assertThat(generated, containsString("/recorded_two"));
        assertThat(generated, not(containsString(".withId(")));
        assertThat(javaAfter.response.getBodyAsString(), is(generated));
    }

    @Test
    public void shouldHandleRetrieveRequestResponsesAsHar() {
        // given
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/test").withMethod("GET").withHeader("host", "example.com"))
                .setHttpResponse(response().withStatusCode(200).withReasonPhrase("OK").withBody("hello"))
                .setExpectation(new Expectation(request("/api/test"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response().withStatusCode(200).withReasonPhrase("OK").withBody("hello")))
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", REQUEST_RESPONSES.name())
            .withQueryStringParameter("format", "HAR")
            .withBody(
                requestDefinitionSerializer.serialize(request("/api/test"))
            );
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"version\" : \"1.2\""));
        assertThat(body, containsString("\"creator\""));
        assertThat(body, containsString("\"MockServer\""));
        assertThat(body, containsString("\"entries\""));
        assertThat(body, containsString("\"method\" : \"GET\""));
        assertThat(body, containsString("http://example.com/api/test"));
        assertThat(body, containsString("\"status\" : 200"));
        assertThat(body, containsString("\"statusText\" : \"OK\""));
    }

    @Test
    public void shouldHandleRetrieveRequestResponsesAsCurl() {
        // given
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/test").withMethod("GET").withHeader("host", "example.com"))
                .setHttpResponse(response().withStatusCode(200).withReasonPhrase("OK").withBody("hello"))
                .setExpectation(new Expectation(request("/api/test"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response().withStatusCode(200).withReasonPhrase("OK").withBody("hello")))
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", REQUEST_RESPONSES.name())
            .withQueryStringParameter("format", "CURL")
            .withBody(
                requestDefinitionSerializer.serialize(request("/api/test"))
            );
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("curl -v"));
        assertThat(body, containsString("http://example.com/api/test"));
    }

    // -----------------------------------------------------------------------------------------
    // Coverage for the (type, format) retrieve-dispatch branches that ship export/collection
    // formats but previously had no HttpState-level test. Each assertion below checks a
    // format-appropriate marker so that a regression which fell through to the plain-text
    // "not supported" branch would fail the test rather than pass silently.
    // -----------------------------------------------------------------------------------------

    private HttpRequest retrieveRequest(RetrieveType type, String format, HttpRequest matcher) {
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", type.name())
            .withQueryStringParameter("format", format);
        if (matcher != null) {
            retrieveRequest.withBody(requestDefinitionSerializer.serialize(matcher));
        }
        return retrieveRequest;
    }

    @Test
    public void shouldHandleRetrieveRequestsInExportFormats() {
        // given
        httpState.log(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/api/test").withMethod("GET").withHeader("host", "example.com"))
        );

        // OPENAPI
        FakeResponseWriter openApiWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.REQUESTS, "OPENAPI", request("/api/test")), openApiWriter, false), is(true));
        assertThat(openApiWriter.response.getStatusCode(), is(200));
        String openApiBody = openApiWriter.response.getBodyAsString();
        assertThat(openApiBody, not(containsString("not supported")));
        assertThat(openApiBody, containsString("\"openapi\""));
        assertThat(openApiBody, containsString("\"info\""));

        // POSTMAN
        FakeResponseWriter postmanWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.REQUESTS, "POSTMAN", request("/api/test")), postmanWriter, false), is(true));
        assertThat(postmanWriter.response.getStatusCode(), is(200));
        String postmanBody = postmanWriter.response.getBodyAsString();
        assertThat(postmanBody, not(containsString("not supported")));
        assertThat(postmanBody, containsString("\"item\""));
        assertThat(postmanBody, containsString("schema.getpostman.com"));

        // BRUNO — zip download, assert headers rather than body equality
        FakeResponseWriter brunoWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.REQUESTS, "BRUNO", request("/api/test")), brunoWriter, false), is(true));
        assertThat(brunoWriter.response.getStatusCode(), is(200));
        assertThat(brunoWriter.response.getFirstHeader("content-type"), is("application/zip"));
        assertThat(brunoWriter.response.getFirstHeader("content-disposition"), containsString("attachment"));
        assertThat(brunoWriter.response.getFirstHeader("content-disposition"), containsString(".bruno.zip"));

        // HAR
        FakeResponseWriter harWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.REQUESTS, "HAR", request("/api/test")), harWriter, false), is(true));
        assertThat(harWriter.response.getStatusCode(), is(200));
        String harBody = harWriter.response.getBodyAsString();
        assertThat(harBody, not(containsString("not supported")));
        assertThat(harBody, containsString("\"version\" : \"1.2\""));
        assertThat(harBody, containsString("\"entries\""));
        assertThat(harBody, containsString("/api/test"));
    }

    @Test
    public void shouldHandleRetrieveRequestResponsesInExportFormats() {
        // given
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/test").withMethod("GET").withHeader("host", "example.com"))
                .setHttpResponse(response().withStatusCode(200).withReasonPhrase("OK").withBody("hello"))
                .setExpectation(new Expectation(request("/api/test"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response().withStatusCode(200).withReasonPhrase("OK").withBody("hello")))
        );

        // OPENAPI
        FakeResponseWriter openApiWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(REQUEST_RESPONSES, "OPENAPI", request("/api/test")), openApiWriter, false), is(true));
        assertThat(openApiWriter.response.getStatusCode(), is(200));
        String openApiBody = openApiWriter.response.getBodyAsString();
        assertThat(openApiBody, not(containsString("not supported")));
        assertThat(openApiBody, containsString("\"openapi\""));
        assertThat(openApiBody, containsString("\"info\""));

        // POSTMAN
        FakeResponseWriter postmanWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(REQUEST_RESPONSES, "POSTMAN", request("/api/test")), postmanWriter, false), is(true));
        assertThat(postmanWriter.response.getStatusCode(), is(200));
        String postmanBody = postmanWriter.response.getBodyAsString();
        assertThat(postmanBody, not(containsString("not supported")));
        assertThat(postmanBody, containsString("\"item\""));
        assertThat(postmanBody, containsString("schema.getpostman.com"));

        // BRUNO — zip download, assert headers rather than body equality
        FakeResponseWriter brunoWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(REQUEST_RESPONSES, "BRUNO", request("/api/test")), brunoWriter, false), is(true));
        assertThat(brunoWriter.response.getStatusCode(), is(200));
        assertThat(brunoWriter.response.getFirstHeader("content-type"), is("application/zip"));
        assertThat(brunoWriter.response.getFirstHeader("content-disposition"), containsString("attachment"));
        assertThat(brunoWriter.response.getFirstHeader("content-disposition"), containsString(".bruno.zip"));
    }

    @Test
    public void shouldHandleRetrieveRecordedExpectationsInExportFormats() {
        // given
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/api/test").withMethod("GET").withHeader("host", "example.com"))
                .setHttpResponse(response().withStatusCode(200).withReasonPhrase("OK").withBody("hello"))
                .setExpectation(new Expectation(request("/api/test"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response().withStatusCode(200).withReasonPhrase("OK").withBody("hello")))
        );

        // LOG_ENTRIES — serialized recorded-expectation log entries (JSON array)
        FakeResponseWriter logEntriesWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS, "LOG_ENTRIES", request("/api/test")), logEntriesWriter, false), is(true));
        assertThat(logEntriesWriter.response.getStatusCode(), is(200));
        String logEntriesBody = logEntriesWriter.response.getBodyAsString();
        assertThat(logEntriesBody, not(containsString("not supported")));
        assertThat(logEntriesBody.trim(), startsWith("["));
        assertThat(logEntriesBody, containsString("/api/test"));

        // OPENAPI
        FakeResponseWriter openApiWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS, "OPENAPI", request("/api/test")), openApiWriter, false), is(true));
        assertThat(openApiWriter.response.getStatusCode(), is(200));
        String openApiBody = openApiWriter.response.getBodyAsString();
        assertThat(openApiBody, not(containsString("not supported")));
        assertThat(openApiBody, containsString("\"openapi\""));
        assertThat(openApiBody, containsString("\"info\""));

        // POSTMAN
        FakeResponseWriter postmanWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS, "POSTMAN", request("/api/test")), postmanWriter, false), is(true));
        assertThat(postmanWriter.response.getStatusCode(), is(200));
        String postmanBody = postmanWriter.response.getBodyAsString();
        assertThat(postmanBody, not(containsString("not supported")));
        assertThat(postmanBody, containsString("\"item\""));
        assertThat(postmanBody, containsString("schema.getpostman.com"));

        // BRUNO — zip download, assert headers rather than body equality
        FakeResponseWriter brunoWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS, "BRUNO", request("/api/test")), brunoWriter, false), is(true));
        assertThat(brunoWriter.response.getStatusCode(), is(200));
        assertThat(brunoWriter.response.getFirstHeader("content-type"), is("application/zip"));
        assertThat(brunoWriter.response.getFirstHeader("content-disposition"), containsString("attachment"));
        assertThat(brunoWriter.response.getFirstHeader("content-disposition"), containsString(".bruno.zip"));

        // HAR
        FakeResponseWriter harWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS, "HAR", request("/api/test")), harWriter, false), is(true));
        assertThat(harWriter.response.getStatusCode(), is(200));
        String harBody = harWriter.response.getBodyAsString();
        assertThat(harBody, not(containsString("not supported")));
        assertThat(harBody, containsString("\"version\" : \"1.2\""));
        assertThat(harBody, containsString("\"entries\""));
        assertThat(harBody, containsString("/api/test"));
    }

    @Test
    public void shouldHandleRetrieveActiveExpectationsInExportFormats() {
        // given
        httpState.add(new Expectation(request("/api/test").withMethod("GET")).withId("key_one").thenRespond(response().withStatusCode(200).withBody("hello")));

        // OPENAPI
        FakeResponseWriter openApiWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.ACTIVE_EXPECTATIONS, "OPENAPI", request("/api/test")), openApiWriter, false), is(true));
        assertThat(openApiWriter.response.getStatusCode(), is(200));
        String openApiBody = openApiWriter.response.getBodyAsString();
        assertThat(openApiBody, not(containsString("not supported")));
        assertThat(openApiBody, containsString("\"openapi\""));
        assertThat(openApiBody, containsString("\"info\""));

        // POSTMAN
        FakeResponseWriter postmanWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.ACTIVE_EXPECTATIONS, "POSTMAN", request("/api/test")), postmanWriter, false), is(true));
        assertThat(postmanWriter.response.getStatusCode(), is(200));
        String postmanBody = postmanWriter.response.getBodyAsString();
        assertThat(postmanBody, not(containsString("not supported")));
        assertThat(postmanBody, containsString("\"item\""));
        assertThat(postmanBody, containsString("schema.getpostman.com"));

        // BRUNO — zip download, assert headers rather than body equality
        FakeResponseWriter brunoWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.ACTIVE_EXPECTATIONS, "BRUNO", request("/api/test")), brunoWriter, false), is(true));
        assertThat(brunoWriter.response.getStatusCode(), is(200));
        assertThat(brunoWriter.response.getFirstHeader("content-type"), is("application/zip"));
        assertThat(brunoWriter.response.getFirstHeader("content-disposition"), containsString("attachment"));
        assertThat(brunoWriter.response.getFirstHeader("content-disposition"), containsString(".bruno.zip"));

        // HAR
        FakeResponseWriter harWriter = new FakeResponseWriter();
        assertThat(httpState.handle(retrieveRequest(RetrieveType.ACTIVE_EXPECTATIONS, "HAR", request("/api/test")), harWriter, false), is(true));
        assertThat(harWriter.response.getStatusCode(), is(200));
        String harBody = harWriter.response.getBodyAsString();
        assertThat(harBody, not(containsString("not supported")));
        assertThat(harBody, containsString("\"version\" : \"1.2\""));
        assertThat(harBody, containsString("\"entries\""));
        assertThat(harBody, containsString("/api/test"));
    }

    @Test
    public void shouldRejectCurlForActiveExpectations() {
        // given
        httpState.add(new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_one")));
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
            .withQueryStringParameter("format", "CURL");
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then — completes cleanly with a clear message rather than hanging
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getBodyAsString(), containsString("CURL not supported for ACTIVE_EXPECTATIONS"));
    }

    @Test
    public void shouldRetrieveActiveExpectationsAsJavaScript() {
        // given
        httpState.add(new Expectation(request("/somePath")).withId("key_one").thenRespond(response("someBody")));
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
            .withQueryStringParameter("format", "JAVASCRIPT");
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("const { mockServerClient } = require('mockserver-client');"));
        assertThat(body, containsString("mockServerClient(\"localhost\", 1080).mockAnyResponse("));
        assertThat(body, containsString("/somePath"));
    }

    @Test
    public void shouldRetrieveActiveExpectationsAsPython() {
        // given
        httpState.add(new Expectation(request("/somePath")).withId("key_one").thenRespond(response("someBody")));
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
            .withQueryStringParameter("format", "PYTHON");
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("from mockserver import MockServerClient, Expectation"));
        assertThat(body, containsString("client.upsert(Expectation.from_dict(json.loads("));
        assertThat(body, containsString("/somePath"));
    }

    @Test
    public void shouldHandleRetrieveRequestsAsCurl() {
        // given
        httpState.log(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/api/test").withMethod("GET").withHeader("host", "example.com"))
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.REQUESTS.name())
            .withQueryStringParameter("format", "CURL")
            .withBody(requestDefinitionSerializer.serialize(request("/api/test")));
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("curl -v"));
        assertThat(body, containsString("http://example.com/api/test"));
    }

    @Test
    public void shouldRejectCurlForRecordedExpectations() {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.RECORDED_EXPECTATIONS.name())
            .withQueryStringParameter("format", "CURL");
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then — completes cleanly with a clear message rather than hanging
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getBodyAsString(), containsString("CURL not supported for RECORDED_EXPECTATIONS"));
    }

    @Test
    public void shouldHandleRetrieveLogMessagesRequest() {
        // given
        configuration.logLevel(Level.INFO);
        httpState.add(new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_one")));
        FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            HttpRequest retrieveLogRequest = request("/mockserver/retrieve")
                .withMethod("PUT")
                .withQueryStringParameter("type", RetrieveType.LOGS.name())
                .withBody(
                    requestDefinitionSerializer.serialize(request("request_one"))
                );
            boolean handle = httpState.handle(retrieveLogRequest, responseWriter, false);

            // then
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(200));
            assertThat(
                responseWriter.response.getBodyAsString(),
                is(endsWith(LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - creating expectation:" + NEW_LINE +
                    NEW_LINE +
                    "  {" + NEW_LINE +
                    "    \"httpRequest\" : {" + NEW_LINE +
                    "      \"path\" : \"request_one\"" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"httpResponse\" : {" + NEW_LINE +
                    "      \"statusCode\" : 200," + NEW_LINE +
                    "      \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                    "      \"body\" : \"response_one\"" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"id\" : \"key_one\"," + NEW_LINE +
                    "    \"priority\" : 0," + NEW_LINE +
                    "    \"timeToLive\" : {" + NEW_LINE +
                    "      \"unlimited\" : true" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"times\" : {" + NEW_LINE +
                    "      \"unlimited\" : true" + NEW_LINE +
                    "    }" + NEW_LINE +
                    "  }" + NEW_LINE +
                    NEW_LINE +
                    " with id:" + NEW_LINE +
                    NEW_LINE +
                    "  key_one" + NEW_LINE +
                    NEW_LINE))
            );
    }

    @Test
    public void shouldHandleAddExpectationRequest() {
        // given
        Expectation expectationOne = new Expectation(request("request_one")).thenRespond(response("response_one"));
        HttpRequest request = request("/mockserver/expectation").withMethod("PUT").withBody(
            expectationSerializer.serialize(expectationOne)
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(request, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        assertThat(responseWriter.response.getBodyAsString(), CoreMatchers.containsString("[ {" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"path\" : \"request_one\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200," + NEW_LINE +
            "    \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "    \"body\" : \"response_one\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"id\" : \""));
        assertThat(responseWriter.response.getBodyAsString(), CoreMatchers.containsString("\"," + NEW_LINE +
            "  \"priority\" : 0," + NEW_LINE +
            "  \"timeToLive\" : {" + NEW_LINE +
            "    \"unlimited\" : true" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"unlimited\" : true" + NEW_LINE +
            "  }" + NEW_LINE +
            "} ]"));
        assertThat(httpState.firstMatchingExpectation(request("request_one")), is(expectationOne));
    }

    @Test
    public void shouldHandleAddOpenAPIJsonRequest() throws JsonProcessingException, InterruptedException {
        // given
        HttpRequest request = request("/mockserver/openapi").withMethod("PUT").withBody(
            openAPIExpectationSerializer.serialize(openAPIExpectation(
                FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json")
            ))
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(request, responseWriter, false);
        responseWriter.awaitResponse();

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        List<Expectation> actualExpectations = Arrays.asList(expectationSerializer.deserializeArray(responseWriter.response.getBodyAsString(), true));
        shouldBuildPetStoreExpectations(ObjectMapperFactory.createObjectMapper().readTree(FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json")).toPrettyString(), actualExpectations);
    }

    @Test
    public void shouldHandleAddOpenAPIJsonRequestWithSpecificResponses() throws JsonProcessingException, InterruptedException {
        // given
        HttpRequest request = request("/mockserver/openapi").withMethod("PUT").withBody(
            openAPIExpectationSerializer.serialize(openAPIExpectation(
                FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json"), ImmutableMap.of(
                    "listPets", "500",
                    "createPets", "default",
                    "showPetById", "200"
                )
            ))
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(request, responseWriter, false);
        responseWriter.awaitResponse();

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        List<Expectation> actualExpectations = Arrays.asList(expectationSerializer.deserializeArray(responseWriter.response.getBodyAsString(), true));
        shouldBuildPetStoreExpectationsWithSpecificResponses(ObjectMapperFactory.createObjectMapper().readTree(FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json")).toPrettyString(), actualExpectations);
    }

    @Test
    public void shouldHandleInvalidOpenAPIJsonRequest() throws InterruptedException {
        // given
        HttpRequest request = request("/mockserver/openapi").withMethod("PUT").withBody(
            openAPIExpectationSerializer.serialize(openAPIExpectation("" +
                "\"openapi\": \"3.0.0\"," + NEW_LINE +
                "  \"info\": {" + NEW_LINE +
                "    \"version\": \"1.0.0\"," + NEW_LINE +
                "    \"title\": \"Swagger Petstore\""
            ))
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(request, responseWriter, false);
        responseWriter.awaitResponse();

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), is("Unable to load API spec, while parsing a block mapping" + NEW_LINE +
            " in 'reader', line 1, column 1:" + NEW_LINE +
            "    \"openapi\": \"3.0.0\"," + NEW_LINE +
            "    ^" + NEW_LINE +
            "expected <block end>, but found ','" + NEW_LINE +
            " in 'reader', line 1, column 19:" + NEW_LINE +
            "    \"openapi\": \"3.0.0\"," + NEW_LINE +
            "                      ^"));
    }

    @Test
    public void shouldHandleAddOpenAPIYamlRequest() throws InterruptedException {
        // given
        HttpRequest request = request("/mockserver/openapi").withMethod("PUT").withBody(
            openAPIExpectationSerializer.serialize(openAPIExpectation(
                FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.yaml")
            ))
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(request, responseWriter, false);
        responseWriter.awaitResponse();

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        List<Expectation> actualExpectations = Arrays.asList(expectationSerializer.deserializeArray(responseWriter.response.getBodyAsString(), true));
        shouldBuildPetStoreExpectations(FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.yaml"), actualExpectations);
    }

    @Test
    public void shouldHandleAddOpenAPIYamlRequestWithSpecificResponses() throws InterruptedException {
        // given
        HttpRequest request = request("/mockserver/openapi").withMethod("PUT").withBody(
            openAPIExpectationSerializer.serialize(openAPIExpectation(
                FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.yaml"), ImmutableMap.of(
                    "listPets", "500",
                    "createPets", "default",
                    "showPetById", "200"
                )
            ))
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(request, responseWriter, false);
        responseWriter.awaitResponse();

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        List<Expectation> actualExpectations = Arrays.asList(expectationSerializer.deserializeArray(responseWriter.response.getBodyAsString(), true));
        shouldBuildPetStoreExpectationsWithSpecificResponses(FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.yaml"), actualExpectations);
    }

    @Test
    public void shouldHandleInvalidOpenAPIYamlRequest() throws InterruptedException {
        // given
        HttpRequest request = request("/mockserver/openapi").withMethod("PUT").withBody(
            openAPIExpectationSerializer.serialize(openAPIExpectation(
                FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.yaml").substring(0, 100)
            ))
        );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(request, responseWriter, false);
        responseWriter.awaitResponse();

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), is("Unable to load API spec, while scanning a simple key" + NEW_LINE +
            " in 'reader', line 8, column 1:" + NEW_LINE +
            "    servers" + NEW_LINE +
            "    ^" + NEW_LINE +
            "could not find expected ':'" + NEW_LINE +
            " in 'reader', line 8, column 8:" + NEW_LINE +
            "    servers" + NEW_LINE +
            "           ^"));
    }

    @Test
    public void shouldHandleRetrieveActiveExpectationsRequest() {
        // given
        Expectation expectationOne = new Expectation(request("request_one")).thenRespond(response("response_one"));
        httpState.add(expectationOne);
        HttpRequest expectationRetrieveExpectationsRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
            .withBody(
                requestDefinitionSerializer.serialize(request("request_one"))
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(expectationRetrieveExpectationsRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), is(expectationSerializer.serialize(Collections.singletonList(
            expectationOne
        ))));
    }

    @Test
    public void shouldHandleVerifyRequest() {
        // given
        MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("two"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        HttpRequest expectationRetrieveExpectationsRequest = request("/mockserver/verify")
            .withMethod("PUT")
            .withBody(
                verificationSerializer.serialize(
                    new Verification()
                        .withRequest(request("two"))
                )
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(expectationRetrieveExpectationsRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(202));
        assertThat(responseWriter.response.getBodyAsString(), is(""));
    }

    @Test
    public void shouldHandleVerifyFailureRequest() {
        // given
        MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        HttpRequest expectationRetrieveExpectationsRequest = request("/mockserver/verify")
            .withMethod("PUT")
            .withBody(
                verificationSerializer.serialize(
                    new Verification()
                        .withRequest(request("two"))
                )
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(expectationRetrieveExpectationsRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(406));
        assertThat(responseWriter.response.getBodyAsString(), is("Request not found at least once, expected:<{" + NEW_LINE +
            "  \"path\" : \"two\"" + NEW_LINE +
            "}> but was:<{" + NEW_LINE +
            "  \"path\" : \"one\"" + NEW_LINE +
            "}>"));
    }

    @Test
    public void shouldHandleVerifySequenceRequest() {
        // given
        MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("two"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        HttpRequest expectationRetrieveExpectationsRequest = request("/mockserver/verifySequence")
            .withMethod("PUT")
            .withBody(
                verificationSequenceSerializer.serialize(
                    new VerificationSequence()
                        .withRequests(
                            request("one"),
                            request("three")
                        )
                )
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(expectationRetrieveExpectationsRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(202));
        assertThat(responseWriter.response.getBodyAsString(), is(""));
    }

    @Test
    public void shouldHandleVerifySequenceFailureRequest() {
        // given
        MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("two"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        HttpRequest expectationRetrieveExpectationsRequest = request("/mockserver/verifySequence")
            .withMethod("PUT")
            .withBody(
                verificationSequenceSerializer.serialize(
                    new VerificationSequence()
                        .withRequests(
                            request("three"),
                            request("one")
                        )
                )
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(expectationRetrieveExpectationsRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(406));
        assertThat(responseWriter.response.getBodyAsString(), is("Request sequence not found, expected:<[ {" + NEW_LINE +
            "  \"path\" : \"three\"" + NEW_LINE +
            "}, {" + NEW_LINE +
            "  \"path\" : \"one\"" + NEW_LINE +
            "} ]> but was:<[ {" + NEW_LINE +
            "  \"path\" : \"one\"" + NEW_LINE +
            "}, {" + NEW_LINE +
            "  \"path\" : \"two\"" + NEW_LINE +
            "}, {" + NEW_LINE +
            "  \"path\" : \"three\"" + NEW_LINE +
            "} ]>"));
    }

    @Test
    public void shouldAddExceptionsWithNullFields() {
        // given - some existing expectations
        Expectation expectationOne = new Expectation(null).thenRespond(response("response_one"));
        Expectation expectationTwo = new Expectation(request("request_two")).thenRespond((HttpResponse) null);

        // when
        List<Expectation> actualExpectationsOne = httpState.add(expectationOne);
        List<Expectation> actualExpectationsTwo = httpState.add(expectationTwo);

        // then - correct expectations exist
        assertThat(actualExpectationsOne.size(), is(1));
        assertThat(actualExpectationsOne.get(0), is(expectationOne));
        assertThat(actualExpectationsTwo.size(), is(1));
        assertThat(actualExpectationsTwo.get(0), is(expectationTwo));
        assertThat(httpState.firstMatchingExpectation(null), is(expectationOne));
        assertThat(httpState.firstMatchingExpectation(request("request_two")), is(expectationOne));
    }

    @Test
    public void shouldAddExceptionViaOpenApiClasspath() {
        // when
        List<Expectation> actualExpectations = httpState.add(openAPIExpectation("org/mockserver/openapi/openapi_petstore_example.json"));

        // then
        shouldBuildPetStoreExpectations("org/mockserver/openapi/openapi_petstore_example.json", actualExpectations);
    }

    @Test
    public void shouldAddExceptionViaOpenApiUrl() {
        // given
        URL schemaUrl = FilePath.getURL("org/mockserver/openapi/openapi_petstore_example.json");

        // when
        List<Expectation> actualExpectations = httpState.add(openAPIExpectation(String.valueOf(schemaUrl)));

        // then
        shouldBuildPetStoreExpectations(String.valueOf(schemaUrl), actualExpectations);
    }

    @Test
    public void shouldAddExceptionViaOpenApiSpec() {
        // given
        String schema = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");

        // when
        List<Expectation> actualExpectations = httpState.add(openAPIExpectation(schema));

        // then
        shouldBuildPetStoreExpectations(schema, actualExpectations);
    }

    private void shouldBuildPetStoreExpectations(String specUrlOrPayload, List<Expectation> actualExpectations) {
        assertThat(actualExpectations.size(), is(4));
        assertThat(actualExpectations.get(0), is(
            when(specUrlOrPayload, "listPets")
                .thenRespond(
                    response()
                        .withStatusCode(200)
                        .withHeader("x-next", "some_string_value")
                        .withHeader("content-type", "application/json")
                        .withBody(json("[ {" + NEW_LINE +
                            "  \"id\" : 0," + NEW_LINE +
                            "  \"name\" : \"some_string_value\"," + NEW_LINE +
                            "  \"tag\" : \"some_string_value\"" + NEW_LINE +
                            "} ]"))
                )
        ));
        assertThat(actualExpectations.get(1), is(
            when(specUrlOrPayload, "createPets")
                .thenRespond(
                    response()
                        .withStatusCode(201)
                )
        ));
        assertThat(actualExpectations.get(2), is(
            when(specUrlOrPayload, "showPetById")
                .thenRespond(
                    response()
                        .withStatusCode(200)
                        .withHeader("content-type", "application/json")
                        .withBody(json("{" + NEW_LINE +
                            "  \"id\" : 0," + NEW_LINE +
                            "  \"name\" : \"some_string_value\"," + NEW_LINE +
                            "  \"tag\" : \"some_string_value\"" + NEW_LINE +
                            "}"))
                )
        ));
        assertThat(actualExpectations.get(3), is(
            when(specUrlOrPayload, "somePath")
                .thenRespond(
                    response()
                        .withStatusCode(200)
                        .withHeader("content-type", "application/json")
                        .withBody(json("{" + NEW_LINE +
                            "  \"id\" : 0," + NEW_LINE +
                            "  \"name\" : \"some_string_value\"," + NEW_LINE +
                            "  \"tag\" : \"some_string_value\"" + NEW_LINE +
                            "}"))
                )
        ));
    }

    @Test
    public void shouldAddExceptionViaOpenApiClasspathWithSpecificResponses() {
        // when
        List<Expectation> actualExpectations = httpState.add(openAPIExpectation("org/mockserver/openapi/openapi_petstore_example.json", ImmutableMap.of(
            "listPets", "500",
            "createPets", "default",
            "showPetById", "200"
        )));

        // then
        shouldBuildPetStoreExpectationsWithSpecificResponses("org/mockserver/openapi/openapi_petstore_example.json", actualExpectations);
    }

    @Test
    public void shouldAddExceptionViaOpenApiUrlWithSpecificResponses() {
        // given
        URL schemaUrl = FilePath.getURL("org/mockserver/openapi/openapi_petstore_example.json");

        // when
        List<Expectation> actualExpectations = httpState.add(openAPIExpectation(String.valueOf(schemaUrl), ImmutableMap.of(
            "listPets", "500",
            "createPets", "default",
            "showPetById", "200"
        )));

        // then
        shouldBuildPetStoreExpectationsWithSpecificResponses(String.valueOf(schemaUrl), actualExpectations);
    }

    @Test
    public void shouldAddExceptionViaOpenApiSpecWithSpecificResponses() {
        // given
        String schema = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");

        // when
        List<Expectation> actualExpectations = httpState.add(openAPIExpectation(schema, ImmutableMap.of(
            "listPets", "500",
            "createPets", "default",
            "showPetById", "200"
        )));

        // then
        shouldBuildPetStoreExpectationsWithSpecificResponses(schema, actualExpectations);
    }

    private void shouldBuildPetStoreExpectationsWithSpecificResponses(String specUrlOrPayload, List<Expectation> actualExpectations) {
        assertThat(actualExpectations.size(), is(3));
        assertThat(actualExpectations.get(0), is(
            when(specUrlOrPayload, "listPets")
                .thenRespond(
                    response()
                        .withStatusCode(500)
                        .withHeader("content-type", "application/json")
                        .withBody(json("{" + NEW_LINE +
                            "  \"code\" : 0," + NEW_LINE +
                            "  \"message\" : \"some_string_value\"" + NEW_LINE +
                            "}"))
                )
        ));
        assertThat(actualExpectations.get(1), is(
            when(specUrlOrPayload, "createPets")
                .thenRespond(
                    response()
                        .withHeader("content-type", "application/json")
                        .withBody(json("{" + NEW_LINE +
                            "  \"code\" : 0," + NEW_LINE +
                            "  \"message\" : \"some_string_value\"" + NEW_LINE +
                            "}"))
                )
        ));
        assertThat(actualExpectations.get(2), is(
            when(specUrlOrPayload, "showPetById")
                .thenRespond(
                    response()
                        .withStatusCode(200)
                        .withHeader("content-type", "application/json")
                        .withBody(json("{" + NEW_LINE +
                            "  \"id\" : 0," + NEW_LINE +
                            "  \"name\" : \"some_string_value\"," + NEW_LINE +
                            "  \"tag\" : \"some_string_value\"" + NEW_LINE +
                            "}"))
                )
        ));
    }

    @Test
    public void shouldClearLogsAndExpectations() {
        // given
        configuration.logLevel(Level.INFO);
        httpState.add(
                new Expectation(request("request_one"))
                    .withId("one")
                    .thenRespond(response("response_one"))
            );
            // given - some log entries
            httpState.log(
                new LogEntry()
                    .setType(TRACE)
                    .setHttpRequest(request("request_four"))
                    .setExpectation(new Expectation(request("request_four")).thenRespond(response("response_four")))
                    .setMessageFormat("some random{}message")
                    .setArguments("argument_one")
            );

            // when
            httpState
                .clear(
                    request()
                        .withQueryStringParameter("type", "all")
                );

            // then - retrieves correct state
            assertThat(
                httpState
                    .retrieve(
                        request()
                            .withQueryStringParameter("type", "logs")
                    ),
                is(response().withBody("" +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - cleared logs that match:" + NEW_LINE +
                        "" + NEW_LINE +
                        "  {}" + NEW_LINE +
                        "" + NEW_LINE +
                        "------------------------------------" + NEW_LINE +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - removed expectation:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"httpRequest\" : {" + NEW_LINE +
                        "      \"path\" : \"request_one\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"httpResponse\" : {" + NEW_LINE +
                        "      \"statusCode\" : 200," + NEW_LINE +
                        "      \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                        "      \"body\" : \"response_one\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"id\" : \"one\"," + NEW_LINE +
                        "    \"priority\" : 0," + NEW_LINE +
                        "    \"timeToLive\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"times\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        " with id:" + NEW_LINE +
                        NEW_LINE +
                        "  one" + NEW_LINE +
                        NEW_LINE,
                    MediaType.PLAIN_TEXT_UTF_8).withStatusCode(200))
            );
            assertThat(
                httpState
                    .retrieve(
                        request()
                            .withQueryStringParameter("type", "active_expectations")
                    ),
                is(response().withBody("[]", MediaType.JSON_UTF_8).withStatusCode(200))
            );
            assertThat(httpState.firstMatchingExpectation(request("request_one")), nullValue());
    }

    @Test
    public void shouldClearLogsAndExpectationsWithRequestMatcher() {
        // given
        configuration.logLevel(Level.INFO);
        httpState.add(
                new Expectation(request("request_one"))
                    .withId("key_one")
                    .thenRespond(response("response_one"))
            );
            httpState.add(
                new Expectation(request("request_four"))
                    .withId("key_four")
                    .thenRespond(response("response_four"))
            );
            // given - some log entries
            httpState.log(
                new LogEntry()
                    .setType(TRACE)
                    .setLogLevel(INFO)
                    .setHttpRequest(request("request_one"))
                    .setMessageFormat("some random{}message")
                    .setArguments("argument_one")
            );
            httpState.log(
                new LogEntry()
                    .setType(TRACE)
                    .setLogLevel(INFO)
                    .setHttpRequest(request("request_four"))
                    .setMessageFormat("some random{}message")
                    .setArguments("argument_four")
            );

            // when
            httpState
                .clear(
                    request()
                        .withQueryStringParameter("type", "all")
                        .withBody(requestDefinitionSerializer.serialize(request("request_four")))
                );

            // then - retrieves correct state
            assertThat(
                httpState
                    .retrieve(
                        request()
                            .withQueryStringParameter("type", "logs")
                    ),
                is(response().withBody("" +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - creating expectation:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"httpRequest\" : {" + NEW_LINE +
                        "      \"path\" : \"request_one\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"httpResponse\" : {" + NEW_LINE +
                        "      \"statusCode\" : 200," + NEW_LINE +
                        "      \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                        "      \"body\" : \"response_one\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"id\" : \"key_one\"," + NEW_LINE +
                        "    \"priority\" : 0," + NEW_LINE +
                        "    \"timeToLive\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"times\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        " with id:" + NEW_LINE +
                        NEW_LINE +
                        "  key_one" + NEW_LINE +
                        NEW_LINE +
                        "------------------------------------" + NEW_LINE +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - some random" + NEW_LINE +
                        NEW_LINE +
                        "  argument_one" + NEW_LINE +
                        NEW_LINE +
                        " message" + NEW_LINE +
                        "------------------------------------" + NEW_LINE +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - cleared logs that match:" + NEW_LINE +
                        "" + NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"path\" : \"request_four\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        "" + NEW_LINE +
                        "------------------------------------" + NEW_LINE +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - removed expectation:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"httpRequest\" : {" + NEW_LINE +
                        "      \"path\" : \"request_four\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"httpResponse\" : {" + NEW_LINE +
                        "      \"statusCode\" : 200," + NEW_LINE +
                        "      \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                        "      \"body\" : \"response_four\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"id\" : \"key_four\"," + NEW_LINE +
                        "    \"priority\" : 0," + NEW_LINE +
                        "    \"timeToLive\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"times\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        " with id:" + NEW_LINE +
                        NEW_LINE +
                        "  key_four" + NEW_LINE +
                        NEW_LINE +
                        "------------------------------------" + NEW_LINE +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - cleared expectations that match:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"path\" : \"request_four\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE,
                    MediaType.PLAIN_TEXT_UTF_8).withStatusCode(200))
            );
            assertThat(
                httpState
                    .retrieve(
                        request()
                            .withQueryStringParameter("type", "active_expectations")
                    ),
                is(response().withBody("" +
                        "[ {" + NEW_LINE +
                        "  \"httpRequest\" : {" + NEW_LINE +
                        "    \"path\" : \"request_one\"" + NEW_LINE +
                        "  }," + NEW_LINE +
                        "  \"httpResponse\" : {" + NEW_LINE +
                        "    \"statusCode\" : 200," + NEW_LINE +
                        "    \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                        "    \"body\" : \"response_one\"" + NEW_LINE +
                        "  }," + NEW_LINE +
                        "  \"id\" : \"key_one\"," + NEW_LINE +
                        "  \"priority\" : 0," + NEW_LINE +
                        "  \"timeToLive\" : {" + NEW_LINE +
                        "    \"unlimited\" : true" + NEW_LINE +
                        "  }," + NEW_LINE +
                        "  \"times\" : {" + NEW_LINE +
                        "    \"unlimited\" : true" + NEW_LINE +
                        "  }" + NEW_LINE +
                        "} ]",
                    MediaType.JSON_UTF_8).withStatusCode(200))
            );
            assertThat(
                httpState.firstMatchingExpectation(request("request_one")),
                is(
                    new Expectation(request("request_one"))
                        .thenRespond(response("response_one"))
                )
            );
            assertThat(
                httpState.firstMatchingExpectation(request("request_four")),
                nullValue()
            );
    }

    @Test
    public void shouldClearLogsOnly() {
        // given
        configuration.logLevel(Level.INFO);
        httpState.add(
                new Expectation(request("request_one"))
                    .withId("key_one")
                    .thenRespond(response("response_one"))
            );
            // given - some log entries
            httpState.log(
                new LogEntry()
                    .setType(TRACE)
                    .setHttpRequest(request("request_four"))
                    .setExpectation(new Expectation(request("request_four")).withId("key_four").thenRespond(response("response_four")))
                    .setMessageFormat("some random{}message")
                    .setArguments("argument_one")
            );

            // when
            httpState
                .clear(
                    request()
                        .withQueryStringParameter("type", "log")
                );

            // then - retrieves correct state
            assertThat(
                httpState
                    .retrieve(
                        request()
                            .withQueryStringParameter("type", "logs")
                    ),
                is(response().withBody("" +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - cleared logs that match:" + NEW_LINE +
                        NEW_LINE +
                        "  {}" + NEW_LINE +
                        NEW_LINE,
                    MediaType.PLAIN_TEXT_UTF_8).withStatusCode(200))
            );
            assertThat(
                httpState
                    .retrieve(
                        request()
                            .withQueryStringParameter("type", "active_expectations")
                    ),
                is(response().withBody("" +
                        "[ {" + NEW_LINE +
                        "  \"httpRequest\" : {" + NEW_LINE +
                        "    \"path\" : \"request_one\"" + NEW_LINE +
                        "  }," + NEW_LINE +
                        "  \"httpResponse\" : {" + NEW_LINE +
                        "    \"statusCode\" : 200," + NEW_LINE +
                        "    \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                        "    \"body\" : \"response_one\"" + NEW_LINE +
                        "  }," + NEW_LINE +
                        "  \"id\" : \"key_one\"," + NEW_LINE +
                        "  \"priority\" : 0," + NEW_LINE +
                        "  \"timeToLive\" : {" + NEW_LINE +
                        "    \"unlimited\" : true" + NEW_LINE +
                        "  }," + NEW_LINE +
                        "  \"times\" : {" + NEW_LINE +
                        "    \"unlimited\" : true" + NEW_LINE +
                        "  }" + NEW_LINE +
                        "} ]",
                    MediaType.JSON_UTF_8).withStatusCode(200))
            );
            assertThat(
                httpState.firstMatchingExpectation(request("request_one")),
                is(
                    new Expectation(request("request_one"))
                        .thenRespond(response("response_one"))
                )
            );
    }

    @Test
    public void shouldClearExpectationsOnly() {
        // given
        httpState.add(new Expectation(request("request_one")).thenRespond(response("response_one")));
        httpState.add(new Expectation(request("request_two")).thenRespond(response("response_two")));
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setType(EXPECTATION_RESPONSE)
        );
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_two"))
                .setHttpError(error().withResponseBytes("response_two".getBytes(UTF_8)))
                .setType(EXPECTATION_RESPONSE)
        );

        // when
        httpState
            .clear(
                request()
                    .withQueryStringParameter("type", "expectations")
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );

        // then - correct log entries not removed
        HttpResponse retrieve = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", REQUEST_RESPONSES.name())
                    .withQueryStringParameter("format", LOG_ENTRIES.name())
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );
        assertThat(
            retrieve.getBodyAsString(),
            is(new LogEntrySerializer(new MockServerLogger()).serialize(Collections.singletonList(
                new LogEntry()
                    .setHttpRequest(request("request_one"))
                    .setHttpResponse(response("response_one"))
                    .setType(EXPECTATION_RESPONSE)
            )))
        );
        // then - correct expectations removed
        assertThat(httpState.firstMatchingExpectation(request("request_one")), nullValue());
        assertThat(httpState.firstMatchingExpectation(request("request_two")), is(new Expectation(request("request_two")).thenRespond(response("response_two"))));
    }

    @Test
    public void shouldClearExpectationsById() {
        // given
        httpState.add(new Expectation(request("request.*")).withId("one").thenRespond(response("response_one")));
        httpState.add(new Expectation(request("request.*")).withId("two").thenRespond(response("response_two")));
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setType(EXPECTATION_RESPONSE)
        );
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_two"))
                .setHttpError(error().withResponseBytes("response_two".getBytes(UTF_8)))
                .setType(EXPECTATION_RESPONSE)
        );

        // then
        assertThat(httpState.allMatchingExpectation(request()), containsInAnyOrder(
            new Expectation(request("request.*")).thenRespond(response("response_one")),
            new Expectation(request("request.*")).withId("two").thenRespond(response("response_two"))
        ));

        // when
        httpState
            .clear(
                request()
                    .withQueryStringParameter("type", "expectations")
                    .withBody(expectationIdSerializer.serialize(expectationId("one")))
            );

        // then - correct log entries not removed
        HttpResponse retrieve = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", REQUEST_RESPONSES.name())
                    .withQueryStringParameter("format", LOG_ENTRIES.name())
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );
        assertThat(
            retrieve.getBodyAsString(),
            is(new LogEntrySerializer(new MockServerLogger()).serialize(Collections.singletonList(
                new LogEntry()
                    .setHttpRequest(request("request_one"))
                    .setHttpResponse(response("response_one"))
                    .setType(EXPECTATION_RESPONSE)
            )))
        );
        // then - correct expectations removed
        assertThat(httpState.allMatchingExpectation(request()), containsInAnyOrder(
            new Expectation(request("request.*")).withId("two").thenRespond(response("response_two"))
        ));
    }

    @Test
    public void shouldClearAllExpectationsByRequestMatcher() {
        // given
        httpState.add(new Expectation(request("request.*")).withId("one").thenRespond(response("response_one")));
        httpState.add(new Expectation(request("request.*")).withId("two").thenRespond(response("response_two")));
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setType(EXPECTATION_RESPONSE)
        );
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_two"))
                .setHttpError(error().withResponseBytes("response_two".getBytes(UTF_8)))
                .setType(EXPECTATION_RESPONSE)
        );

        // then
        assertThat(httpState.allMatchingExpectation(request()), containsInAnyOrder(
            new Expectation(request("request.*")).thenRespond(response("response_one")),
            new Expectation(request("request.*")).withId("two").thenRespond(response("response_two"))
        ));

        // when
        httpState
            .clear(
                request()
                    .withQueryStringParameter("type", "expectations")
                    .withBody(requestDefinitionSerializer.serialize(request("request.*")))
            );

        // then - correct log entries not removed
        HttpResponse retrieve = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", REQUEST_RESPONSES.name())
                    .withQueryStringParameter("format", LOG_ENTRIES.name())
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );
        assertThat(
            retrieve.getBodyAsString(),
            is(new LogEntrySerializer(new MockServerLogger()).serialize(Collections.singletonList(
                new LogEntry()
                    .setHttpRequest(request("request_one"))
                    .setHttpResponse(response("response_one"))
                    .setType(EXPECTATION_RESPONSE)
            )))
        );
        // then - correct expectations removed
        assertThat(httpState.allMatchingExpectation(request()), emptyIterable());
    }

    @Test
    public void shouldThrowExceptionForInvalidClearType() {
        // given
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage(containsString("\"invalid\" is not a valid value for \"type\" parameter, only the following values are supported [log, expectations, all]"));

        // when
        httpState.clear(request().withQueryStringParameter("type", "invalid"));
    }

    @Test
    public void shouldRetrieveRecordedRequestsAsJson() {
        // given
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_two"))
                .setType(RECEIVED_REQUEST)
        );
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );

        // then
        assertThat(response,
            is(response().withBody(requestDefinitionSerializer.serialize(Arrays.asList(
                request("request_one"),
                request("request_one")
            )), MediaType.JSON_UTF_8).withStatusCode(200))
        );
    }

    @Test
    public void shouldRetrieveRecordedRequestsAsLogEntries() {
        // given
        httpState
            .log(
                new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request("request_one"))
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request("request_one"))
            );
        httpState
            .log(
                new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request("request_two"))
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request("request_two"))
            );
        httpState
            .log(
                new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request("request_one"))
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request("request_one"))
            );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("format", "log_entries")
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );

        // then
        assertThat(response,
            is(response().withBody(new LogEntrySerializer(new MockServerLogger()).serialize(Arrays.asList(
                new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request("request_one"))
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request("request_one")),
                new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request("request_one"))
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request("request_one"))
            )), MediaType.JSON_UTF_8).withStatusCode(200))
        );
    }

    @Test
    public void shouldRetrieveRecordedRequestsAsJava() {
        // given
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_two"))
                .setType(RECEIVED_REQUEST)
        );
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("format", "java")
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );

        // then
        assertThat(response,
            is(response().withBody(requestDefinitionSerializer.serialize(Arrays.asList(
                request("request_one"),
                request("request_one")
            )), MediaType.create("application", "java").withCharset(UTF_8)).withStatusCode(200))
        );
    }

    @Test
    public void shouldRetrieveRecordedRequestResponsesAsJson() {
        // given
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(NO_MATCH_RESPONSE)
                .setHttpRequest(request("request_one"))
                .setExpectation(new Expectation(request("request_one")).thenRespond(response("response_two")))
                .setMessageFormat("no expectation for:{}returning response:{}")
                .setArguments(request("request_one"), notFoundResponse())
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(EXPECTATION_RESPONSE)
                .setHttpRequest(request("request_two"))
                .setHttpResponse(response("response_two"))
                .setMessageFormat("returning error:{}for request:{}for action:{}")
                .setArguments(request("request_two"), response("response_two"), response("response_two"))
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "request_responses")
                    .withBody(requestDefinitionSerializer.serialize(request("request_.*")))
            );

        // then
        assertThat(response,
            is(response().withBody("[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"request_one\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timestamp\" : \"" + LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + "\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"request_two\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"statusCode\" : 200," + NEW_LINE +
                "    \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                "    \"body\" : \"response_two\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timestamp\" : \"" + LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + "\"" + NEW_LINE +
                "} ]", MediaType.JSON_UTF_8).withStatusCode(200))
        );
    }

    @Test
    public void shouldSerializeRequestResponsesRetrieveOffTheLogConsumerThread() throws Exception {
        // bug #3: serializing the whole REQUEST_RESPONSES list inside the single disruptor log-consumer
        // callback (thread "MockServer-EventLog*") both raced the retrieve future timeout and stalled all
        // further logging. The fix materializes the (cheap) list on the consumer thread but performs the
        // heavy serialize(...) on the CALLER thread. Prove that by recording the thread serialize() runs on.

        // given - a recording serializer injected in place of the real one
        final java.util.concurrent.atomic.AtomicReference<String> serializeThreadName = new java.util.concurrent.atomic.AtomicReference<>();
        LogEventRequestAndResponseSerializer recordingSerializer = new LogEventRequestAndResponseSerializer(new MockServerLogger()) {
            @Override
            public void serialize(java.util.List<org.mockserver.model.LogEventRequestAndResponse> httpRequestAndHttpResponses, java.io.Writer writer) {
                serializeThreadName.set(Thread.currentThread().getName());
                super.serialize(httpRequestAndHttpResponses, writer);
            }
        };
        java.lang.reflect.Field field = HttpState.class.getDeclaredField("httpRequestResponseSerializer");
        field.setAccessible(true);
        field.set(httpState, recordingSerializer);

        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(EXPECTATION_RESPONSE)
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(EXPECTATION_RESPONSE)
                .setHttpRequest(request("request_two"))
                .setHttpResponse(response("response_two"))
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "request_responses")
                    .withQueryStringParameter("format", "json")
                    .withBody(requestDefinitionSerializer.serialize(request("request_.*")))
            );

        // then - retrieve completed with the correct content
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("request_one"));
        assertThat(response.getBodyAsString(), containsString("request_two"));
        // and - serialization ran on the caller (test) thread, NOT on the disruptor log-consumer thread
        assertThat(serializeThreadName.get(), is(notNullValue()));
        assertThat(serializeThreadName.get(), not(startsWith("MockServer-EventLog")));
        assertThat(serializeThreadName.get(), is(Thread.currentThread().getName()));
    }

    @Test
    public void shouldRetrieveManyLargeRequestResponsesWithoutTimingOut() {
        // bug #3 regression guard: a log holding many large captured response bodies must serialize and
        // return from a REQUEST_RESPONSES JSON retrieve without throwing a TimeoutException — the heavy
        // serialization is no longer gated by the retrieve future timeout because it runs on the caller
        // thread rather than inside the single log-consumer callback.

        // given - a dedicated HttpState whose log retains all the entries we are about to write
        final int entryCount = 2000;
        final int bodyBytes = 64 * 1024;
        Configuration largeLogConfiguration = configuration().maxLogEntries(entryCount + 100);
        Scheduler scheduler = mock(Scheduler.class);
        org.mockito.Mockito.when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        HttpState largeLogState = new HttpState(largeLogConfiguration, new MockServerLogger(largeLogConfiguration, MockServerLogger.class), scheduler);
        try {
            StringBuilder largeBodyBuilder = new StringBuilder(bodyBytes);
            for (int i = 0; i < bodyBytes; i++) {
                largeBodyBuilder.append('x');
            }
            String largeBody = largeBodyBuilder.toString();
            for (int i = 0; i < entryCount; i++) {
                largeLogState.log(
                    new LogEntry()
                        .setLogLevel(INFO)
                        .setType(EXPECTATION_RESPONSE)
                        .setHttpRequest(request("/req-" + i))
                        .setHttpResponse(response(largeBody))
                );
            }

            // when
            long start = System.currentTimeMillis();
            HttpResponse response = largeLogState
                .retrieve(
                    request()
                        .withQueryStringParameter("type", "request_responses")
                        .withQueryStringParameter("format", "json")
                        .withBody(requestDefinitionSerializer.serialize(request("/req-.*")))
                );
            long durationMillis = System.currentTimeMillis() - start;

            // then - completed (no TimeoutException) and returned every entry
            assertThat(response.getStatusCode(), is(200));
            assertThat(durationMillis, lessThan(largeLogConfiguration.maxFutureTimeoutInMillis()));
            String body = response.getBodyAsString();
            assertThat(body, containsString("/req-0\""));
            assertThat(body, containsString("/req-" + (entryCount - 1) + "\""));
            int pathCount = 0;
            for (int idx = body.indexOf("\"path\""); idx >= 0; idx = body.indexOf("\"path\"", idx + 1)) {
                pathCount++;
            }
            assertThat(pathCount, is(entryCount));
        } finally {
            largeLogState.stop();
        }
    }

    @Test
    public void shouldRetrieveRecordedRequestsResponsesAsLogEntries() {
        // given
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(NO_MATCH_RESPONSE)
                .setHttpRequest(request("request_one"))
                .setExpectation(new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_two")))
                .setMessageFormat("no expectation for:{}returning response:{}")
                .setArguments(request("request_one"), notFoundResponse())
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(EXPECTATION_RESPONSE)
                .setHttpRequest(request("request_two"))
                .setHttpResponse(response("response_two"))
                .setMessageFormat("returning error:{}for request:{}for action:{}")
                .setArguments(request("request_two"), response("response_two"), response("response_two"))
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "request_responses")
                    .withQueryStringParameter("format", "log_entries")
                    .withBody(requestDefinitionSerializer.serialize(request("request_.*")))
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        String body = response.getBodyAsString();
        assertThat(body, containsString("\"type\" : \"NO_MATCH_RESPONSE\""));
        assertThat(body, containsString("\"type\" : \"EXPECTATION_RESPONSE\""));
        assertThat(body, containsString("\"epochTime\" :"));
        assertThat(body, containsString("\"messageFormat\" : \"no expectation for:{}returning response:{}\""));
        assertThat(body, containsString("\"messageFormat\" : \"returning error:{}for request:{}for action:{}\""));
        assertThat(body, containsString("\"arguments\" :"));
        assertThat(body, containsString("\"logLevel\" : \"INFO\""));
        assertThat(body, containsString("\"path\" : \"request_one\""));
        assertThat(body, containsString("\"path\" : \"request_two\""));
    }

    @Test
    public void shouldRetrieveRecordedRequestsResponsesAsJava() {
        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("format", "java")
                    .withQueryStringParameter("type", "request_responses")
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );

        // then
        assertThat(response.getBodyAsString(), is("JAVA not supported for REQUEST_RESPONSES"));
    }

    @Test
    public void shouldRetrieveRecordedExpectationsAsJson() {
        // given
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setExpectation(new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("response_one")))
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("request_two"))
                .setHttpResponse(response("response_two"))
                .setExpectation(new Expectation(request("request_two"), Times.once(), TimeToLive.unlimited(), 0).withId("key_two").thenRespond(response("response_two")))
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "recorded_expectations")
                    .withQueryStringParameter("format", "json")
            );

        // then
        assertThat(response,
            is(response().withBody(expectationSerializerWithDefaultFields.serialize(Arrays.asList(
                new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("response_one")),
                new Expectation(request("request_two"), Times.once(), TimeToLive.unlimited(), 0).withId("key_two").thenRespond(response("response_two"))
            )), MediaType.JSON_UTF_8).withStatusCode(200))
        );
    }

    @Test
    public void shouldRetrieveRecordedExpectationsAsJava() {
        // given
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setExpectation(request("request_one"), response("response_one"))
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("request_two"))
                .setHttpResponse(response("response_two"))
                .setExpectation(request("request_two"), response("response_two"))
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "recorded_expectations")
                    .withQueryStringParameter("format", "java")
            );

        // then
        assertThat(response,
            is(response().withBody(expectationToJavaSerializer.serialize(Arrays.asList(
                new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).thenRespond(response("response_one")),
                new Expectation(request("request_two"), Times.once(), TimeToLive.unlimited(), 0).thenRespond(response("response_two"))
            )), MediaType.create("application", "java").withCharset(UTF_8)).withStatusCode(200))
        );
    }

    @Test
    public void shouldRetrieveRecordedExpectationsAsJavaWithRequestMatcher() {
        // given
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setExpectation(request("request_one"), response("response_one"))
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("request_two"))
                .setHttpResponse(response("response_two"))
                .setExpectation(request("request_two"), response("response_two"))
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "recorded_expectations")
                    .withQueryStringParameter("format", "java")
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );

        // then
        assertThat(response,
            is(response().withBody(expectationToJavaSerializer.serialize(Collections.singletonList(
                new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).thenRespond(response("response_one"))
            )), MediaType.create("application", "java").withCharset(UTF_8)).withStatusCode(200))
        );
    }

    @Test
    public void shouldRetrieveRecordedExpectationsAsJavaScript() {
        // given
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setExpectation(request("request_one"), response("response_one"))
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("request_two"))
                .setHttpResponse(response("response_two"))
                .setExpectation(request("request_two"), response("response_two"))
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "recorded_expectations")
                    .withQueryStringParameter("format", "javascript")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBody().getContentType(), is(MediaType.create("application", "javascript").withCharset(UTF_8).toString()));
        String body = response.getBodyAsString();
        // import preamble emitted exactly once
        assertThat(body.split("require\\('mockserver-client'\\)", -1).length - 1, is(1));
        // one client call per recorded expectation, each wrapping the expectation JSON
        assertThat(body.split("mockAnyResponse\\(", -1).length - 1, is(2));
        assertThat(body, containsString("mockServerClient(\"localhost\", 1080).mockAnyResponse("));
        assertThat(body, containsString("request_one"));
        assertThat(body, containsString("request_two"));
        assertThat(body, containsString("response_one"));
        assertThat(body, containsString("response_two"));
    }

    @Test
    public void shouldRetrieveRecordedExpectationsAsPython() {
        // given
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setExpectation(request("request_one"), response("response_one"))
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("request_two"))
                .setHttpResponse(response("response_two"))
                .setExpectation(request("request_two"), response("response_two"))
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "recorded_expectations")
                    .withQueryStringParameter("format", "python")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBody().getContentType(), is(MediaType.create("text", "x-python").withCharset(UTF_8).toString()));
        String body = response.getBodyAsString();
        // import preamble emitted exactly once
        assertThat(body.split("import json", -1).length - 1, is(1));
        assertThat(body, containsString("from mockserver import MockServerClient, Expectation"));
        // one client call per recorded expectation, each wrapping the expectation JSON
        assertThat(body.split("client\\.upsert\\(", -1).length - 1, is(2));
        assertThat(body, containsString("client.upsert(Expectation.from_dict(json.loads("));
        assertThat(body, containsString("request_one"));
        assertThat(body, containsString("request_two"));
        assertThat(body, containsString("response_one"));
        assertThat(body, containsString("response_two"));
    }

    @Test
    public void shouldRejectJavaScriptForRequests() {
        // given
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "requests")
                    .withQueryStringParameter("format", "javascript")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("JAVASCRIPT not supported for REQUESTS (use RECORDED_EXPECTATIONS)"));
    }

    @Test
    public void shouldRejectPythonForRequests() {
        // given
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "requests")
                    .withQueryStringParameter("format", "python")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("PYTHON not supported for REQUESTS (use RECORDED_EXPECTATIONS)"));
    }

    @Test
    public void shouldRejectJavaScriptForRequestResponses() {
        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "request_responses")
                    .withQueryStringParameter("format", "javascript")
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );

        // then
        assertThat(response.getBodyAsString(), is("JAVASCRIPT not supported for REQUEST_RESPONSES"));
    }

    @Test
    public void shouldRejectPythonForRequestResponses() {
        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "request_responses")
                    .withQueryStringParameter("format", "python")
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );

        // then
        assertThat(response.getBodyAsString(), is("PYTHON not supported for REQUEST_RESPONSES"));
    }

    @Test
    public void shouldRetrieveActiveExpectationsAsGo() {
        // given
        httpState.add(new Expectation(request("/somePath")).withId("key_one").thenRespond(response("someBody")));
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
            .withQueryStringParameter("format", "GO");
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBody().getContentType(), is(MediaType.create("text", "x-go").withCharset(UTF_8).toString()));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("mockserver \"github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7\""));
        assertThat(body, containsString("client := mockserver.New(\"localhost\", 1080)"));
        assertThat(body, containsString("client.Upsert(e)"));
        assertThat(body, containsString("/somePath"));
    }

    @Test
    public void shouldRetrieveActiveExpectationsAsCSharp() {
        // given
        httpState.add(new Expectation(request("/somePath")).withId("key_one").thenRespond(response("someBody")));
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
            .withQueryStringParameter("format", "CSHARP");
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBody().getContentType(), is(MediaType.create("text", "x-csharp").withCharset(UTF_8).toString()));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("using MockServer.Client;"));
        assertThat(body, containsString("new MockServerClient(\"localhost\", 1080)"));
        assertThat(body, containsString("client.Upsert(JsonSerializer.Deserialize<Expectation>(@\""));
        assertThat(body, containsString("/somePath"));
    }

    @Test
    public void shouldRetrieveActiveExpectationsAsRuby() {
        // given
        httpState.add(new Expectation(request("/somePath")).withId("key_one").thenRespond(response("someBody")));
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
            .withQueryStringParameter("format", "RUBY");
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBody().getContentType(), is(MediaType.create("text", "x-ruby").withCharset(UTF_8).toString()));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("require 'mockserver-client'"));
        assertThat(body, containsString("MockServer::Client.new('localhost', 1080)"));
        assertThat(body, containsString("client.upsert(MockServer::Expectation.from_hash(JSON.parse(<<JSON)))"));
        assertThat(body, containsString("/somePath"));
    }

    @Test
    public void shouldRetrieveActiveExpectationsAsRust() {
        // given
        httpState.add(new Expectation(request("/somePath")).withId("key_one").thenRespond(response("someBody")));
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
            .withQueryStringParameter("format", "RUST");
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBody().getContentType(), is(MediaType.create("text", "x-rust").withCharset(UTF_8).toString()));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("use mockserver_client::{ClientBuilder, Expectation};"));
        assertThat(body, containsString("ClientBuilder::new(\"localhost\", 1080).build()?"));
        assertThat(body, containsString("client.upsert(&[serde_json::from_str::<Expectation>(r#\""));
        assertThat(body, containsString("/somePath"));
    }

    @Test
    public void shouldRetrieveActiveExpectationsAsPhp() {
        // given
        httpState.add(new Expectation(request("/somePath")).withId("key_one").thenRespond(response("someBody")));
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
            .withQueryStringParameter("format", "PHP");
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBody().getContentType(), is(MediaType.create("application", "x-httpd-php").withCharset(UTF_8).toString()));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("use MockServer\\MockServerClient;"));
        assertThat(body, containsString("$client = new MockServerClient('localhost', 1080);"));
        assertThat(body, containsString("$client->upsertExpectation(Expectation::fromArray(json_decode(<<<'JSON'"));
        assertThat(body, containsString("/somePath"));
    }

    @Test
    public void shouldRetrieveRecordedExpectationsAsGo() {
        // given
        logTwoRecordedExpectations();

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "recorded_expectations")
                    .withQueryStringParameter("format", "go")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBody().getContentType(), is(MediaType.create("text", "x-go").withCharset(UTF_8).toString()));
        String body = response.getBodyAsString();
        // import preamble emitted exactly once
        assertThat(body.split("package main", -1).length - 1, is(1));
        // one client call per recorded expectation
        assertThat(body.split("client\\.Upsert\\(e\\)", -1).length - 1, is(2));
        assertThat(body, containsString("request_one"));
        assertThat(body, containsString("request_two"));
    }

    @Test
    public void shouldRetrieveRecordedExpectationsAsCSharp() {
        // given
        logTwoRecordedExpectations();

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "recorded_expectations")
                    .withQueryStringParameter("format", "csharp")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBody().getContentType(), is(MediaType.create("text", "x-csharp").withCharset(UTF_8).toString()));
        String body = response.getBodyAsString();
        assertThat(body.split("using MockServer.Client;", -1).length - 1, is(1));
        assertThat(body.split("client\\.Upsert\\(JsonSerializer", -1).length - 1, is(2));
        assertThat(body, containsString("request_one"));
        assertThat(body, containsString("request_two"));
    }

    @Test
    public void shouldRetrieveRecordedExpectationsAsRuby() {
        // given
        logTwoRecordedExpectations();

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "recorded_expectations")
                    .withQueryStringParameter("format", "ruby")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBody().getContentType(), is(MediaType.create("text", "x-ruby").withCharset(UTF_8).toString()));
        String body = response.getBodyAsString();
        assertThat(body.split("require 'mockserver-client'", -1).length - 1, is(1));
        assertThat(body.split("client\\.upsert\\(MockServer::Expectation", -1).length - 1, is(2));
        assertThat(body, containsString("request_one"));
        assertThat(body, containsString("request_two"));
    }

    @Test
    public void shouldRetrieveRecordedExpectationsAsRust() {
        // given
        logTwoRecordedExpectations();

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "recorded_expectations")
                    .withQueryStringParameter("format", "rust")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBody().getContentType(), is(MediaType.create("text", "x-rust").withCharset(UTF_8).toString()));
        String body = response.getBodyAsString();
        assertThat(body.split("use mockserver_client", -1).length - 1, is(1));
        assertThat(body.split("client\\.upsert\\(&\\[serde_json", -1).length - 1, is(2));
        assertThat(body, containsString("request_one"));
        assertThat(body, containsString("request_two"));
    }

    @Test
    public void shouldRetrieveRecordedExpectationsAsPhp() {
        // given
        logTwoRecordedExpectations();

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "recorded_expectations")
                    .withQueryStringParameter("format", "php")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBody().getContentType(), is(MediaType.create("application", "x-httpd-php").withCharset(UTF_8).toString()));
        String body = response.getBodyAsString();
        assertThat(body.split("use MockServer\\\\MockServerClient;", -1).length - 1, is(1));
        assertThat(body.split("\\$client->upsertExpectation\\(Expectation::fromArray", -1).length - 1, is(2));
        assertThat(body, containsString("request_one"));
        assertThat(body, containsString("request_two"));
    }

    @Test
    public void shouldRejectGoForRequests() {
        assertRejectedForRequests("go", "GO not supported for REQUESTS (use RECORDED_EXPECTATIONS)");
    }

    @Test
    public void shouldRejectCSharpForRequests() {
        assertRejectedForRequests("csharp", "CSHARP not supported for REQUESTS (use RECORDED_EXPECTATIONS)");
    }

    @Test
    public void shouldRejectRubyForRequests() {
        assertRejectedForRequests("ruby", "RUBY not supported for REQUESTS (use RECORDED_EXPECTATIONS)");
    }

    @Test
    public void shouldRejectRustForRequests() {
        assertRejectedForRequests("rust", "RUST not supported for REQUESTS (use RECORDED_EXPECTATIONS)");
    }

    @Test
    public void shouldRejectPhpForRequests() {
        assertRejectedForRequests("php", "PHP not supported for REQUESTS (use RECORDED_EXPECTATIONS)");
    }

    private void logTwoRecordedExpectations() {
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setExpectation(request("request_one"), response("response_one"))
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("request_two"))
                .setHttpResponse(response("response_two"))
                .setExpectation(request("request_two"), response("response_two"))
        );
    }

    private void assertRejectedForRequests(String format, String expectedMessage) {
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "requests")
                    .withQueryStringParameter("format", format)
            );
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is(expectedMessage));
    }

    @Test
    public void shouldRetrieveActiveExpectationsAsJson() {
        // given
        Expectation expectationOne = new Expectation(request("request_one")).thenRespond(response("response_one"));
        httpState.add(expectationOne);
        Expectation expectationTwo = new Expectation(request("request_two")).thenRespond(response("response_two"));
        httpState.add(expectationTwo);
        Expectation expectationThree = new Expectation(request("request_one")).thenRespond(response("request_three"));
        httpState.add(expectationThree);

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "active_expectations")
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );

        // then
        assertThat(response,
            is(response().withBody(expectationSerializer.serialize(Arrays.asList(
                expectationOne,
                expectationThree
            )), MediaType.JSON_UTF_8).withStatusCode(200))
        );
    }

    @Test
    public void shouldRetrieveActiveExpectationsByExpectationId() {
        // given - two expectations that both match the same request, so filtering by the
        // request definition the id resolves to would return both
        Expectation expectationOne = new Expectation(request("request_one")).withId("one").thenRespond(response("response_one"));
        httpState.add(expectationOne);
        Expectation expectationTwo = new Expectation(request("request_two")).withId("two").thenRespond(response("response_two"));
        httpState.add(expectationTwo);
        Expectation expectationThree = new Expectation(request("request_one")).withId("three").thenRespond(response("response_three"));
        httpState.add(expectationThree);

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "active_expectations")
                    .withBody(expectationIdSerializer.serialize(expectationId("one")))
            );

        // then - only the expectation with that id, not every expectation matching the same request
        assertThat(response,
            is(response().withBody(expectationSerializer.serialize(Collections.singletonList(
                expectationOne
            )), MediaType.JSON_UTF_8).withStatusCode(200))
        );
    }

    @Test
    public void shouldRetrieveRecordedRequestsByExpectationId() {
        // given
        httpState.add(new Expectation(request("request_one")).withId("one").thenRespond(response("response_one")));
        httpState.add(new Expectation(request("request_two")).withId("two").thenRespond(response("response_two")));
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_two"))
                .setType(RECEIVED_REQUEST)
        );
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withBody(expectationIdSerializer.serialize(expectationId("one")))
            );

        // then
        assertThat(response,
            is(response().withBody(requestDefinitionSerializer.serialize(Arrays.asList(
                request("request_one"),
                request("request_one")
            )), MediaType.JSON_UTF_8).withStatusCode(200))
        );
    }

    @Test
    public void shouldRetrieveRecordedRequestResponsesByExpectationId() {
        // given
        httpState.add(new Expectation(request("request_one")).withId("one").thenRespond(response("response_one")));
        httpState.add(new Expectation(request("request_two")).withId("two").thenRespond(response("response_two")));
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setType(EXPECTATION_RESPONSE)
        );
        httpState.log(
            new LogEntry()
                .setHttpRequest(request("request_two"))
                .setHttpResponse(response("response_two"))
                .setType(EXPECTATION_RESPONSE)
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", REQUEST_RESPONSES.name())
                    .withQueryStringParameter("format", LOG_ENTRIES.name())
                    .withBody(expectationIdSerializer.serialize(expectationId("one")))
            );

        // then
        assertThat(
            response.getBodyAsString(),
            is(new LogEntrySerializer(new MockServerLogger()).serialize(Collections.singletonList(
                new LogEntry()
                    .setHttpRequest(request("request_one"))
                    .setHttpResponse(response("response_one"))
                    .setType(EXPECTATION_RESPONSE)
            )))
        );
    }

    @Test
    public void shouldRetrieveLogMessagesByExpectationId() {
        // given
        httpState.add(new Expectation(request("request_one")).withId("one").thenRespond(response("response_one")));
        httpState.add(new Expectation(request("request_two")).withId("two").thenRespond(response("response_two")));
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setHttpRequest(request("request_one"))
                .setMessageFormat("some random message with a request:{}")
                .setArguments(request("request_one"))
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setHttpRequest(request("request_two"))
                .setMessageFormat("some random message with a request:{}")
                .setArguments(request("request_two"))
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "logs")
                    .withBody(expectationIdSerializer.serialize(expectationId("one")))
            );

        // then - only the log messages for the request the expectation id resolves to
        assertThat(response.getBodyAsString(), containsString("some random message with a request:" + NEW_LINE + NEW_LINE + "  {" + NEW_LINE + "    \"path\" : \"request_one\""));
        assertThat(response.getBodyAsString(), not(containsString("\"path\" : \"request_two\"")));
    }

    @Test
    public void shouldReturnErrorForRetrieveByUnknownExpectationId() {
        // given
        httpState.add(new Expectation(request("request_one")).withId("one").thenRespond(response("response_one")));

        // then
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage(containsString("No expectation found with id does_not_exist"));

        // when
        httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "active_expectations")
                    .withBody(expectationIdSerializer.serialize(expectationId("does_not_exist")))
            );
    }

    @Test
    public void shouldRetrieveActiveExpectationsAsJava() {
        // given
        Expectation expectationOne = new Expectation(request("request_one")).thenRespond(response("response_one"));
        httpState.add(expectationOne);
        Expectation expectationTwo = new Expectation(request("request_two")).thenRespond(response("response_two"));
        httpState.add(expectationTwo);
        Expectation expectationThree = new Expectation(request("request_one")).thenRespond(response("request_three"));
        httpState.add(expectationThree);

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "active_expectations")
                    .withQueryStringParameter("format", "java")
                    .withBody(requestDefinitionSerializer.serialize(request("request_one")))
            );

        // then
        assertThat(response,
            is(response().withBody(expectationToJavaSerializer.serialize(Arrays.asList(
                expectationOne,
                expectationThree
            )), MediaType.create("application", "java").withCharset(UTF_8)).withStatusCode(200))
        );
    }

    @Test
    public void shouldRetrieveLogEntries() {
        // given
        configuration.logLevel(Level.INFO);
        httpState.log(
                new LogEntry()
                    .setLogLevel(INFO)
                    .setType(NO_MATCH_RESPONSE)
                    .setHttpRequest(request("request_one"))
                    .setExpectation(new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_two")))
                    .setMessageFormat("no expectation for:{}returning response:{}")
                    .setArguments(request("request_one"), notFoundResponse())
            );
            httpState.log(
                new LogEntry()
                    .setLogLevel(INFO)
                    .setType(EXPECTATION_RESPONSE)
                    .setHttpRequest(request("request_two"))
                    .setHttpResponse(response("response_two"))
                    .setMessageFormat("returning error:{}for request:{}for action:{}")
                    .setArguments(request("request_two"), response("response_two"), response("response_two"))
            );
            httpState.log(
                new LogEntry()
                    .setLogLevel(INFO)
                    .setType(EXPECTATION_MATCHED)
                    .setHttpRequest(request("request_one"))
                    .setExpectation(new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_two")))
                    .setMessageFormat("request:{}matched expectation:{}")
                    .setArguments(request("request_one"), new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_two")))
            );
            httpState.log(
                new LogEntry()
                    .setLogLevel(INFO)
                    .setType(EXPECTATION_MATCHED)
                    .setHttpRequest(request("request_two"))
                    .setExpectation(new Expectation(request("request_two")).withId("key_two").thenRespond(response("response_two")))
                    .setMessageFormat("request:{}matched expectation:{}")
                    .setArguments(request("request_two"), new Expectation(request("request_two")).withId("key_two").thenRespond(response("response_two")))
            );
            httpState.log(
                new LogEntry()
                    .setType(TRACE)
                    .setLogLevel(INFO)
                    .setHttpRequest(request("request_four"))
                    .setExpectation(new Expectation(request("request_four")).withId("key_four").thenRespond(response("response_four")))
                    .setMessageFormat("some random{}message")
                    .setArguments("argument_one")
            );

            // when
            HttpResponse response = httpState
                .retrieve(
                    request()
                        .withQueryStringParameter("type", "logs")
                );

            // then
            assertThat(response,
                is(response().withBody("" +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - no expectation for:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"path\" : \"request_one\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        " returning response:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"statusCode\" : 404," + NEW_LINE +
                        "    \"reasonPhrase\" : \"Not Found\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        "------------------------------------" + NEW_LINE +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - returning error:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"path\" : \"request_two\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        " for request:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"statusCode\" : 200," + NEW_LINE +
                        "    \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                        "    \"body\" : \"response_two\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        " for action:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"statusCode\" : 200," + NEW_LINE +
                        "    \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                        "    \"body\" : \"response_two\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        "------------------------------------" + NEW_LINE +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - request:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"path\" : \"request_one\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        " matched expectation:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"httpRequest\" : {" + NEW_LINE +
                        "      \"path\" : \"request_one\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"httpResponse\" : {" + NEW_LINE +
                        "      \"statusCode\" : 200," + NEW_LINE +
                        "      \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                        "      \"body\" : \"response_two\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"id\" : \"key_one\"," + NEW_LINE +
                        "    \"priority\" : 0," + NEW_LINE +
                        "    \"timeToLive\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"times\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        "------------------------------------" + NEW_LINE +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - request:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"path\" : \"request_two\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        " matched expectation:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"httpRequest\" : {" + NEW_LINE +
                        "      \"path\" : \"request_two\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"httpResponse\" : {" + NEW_LINE +
                        "      \"statusCode\" : 200," + NEW_LINE +
                        "      \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                        "      \"body\" : \"response_two\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"id\" : \"key_two\"," + NEW_LINE +
                        "    \"priority\" : 0," + NEW_LINE +
                        "    \"timeToLive\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"times\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        "------------------------------------" + NEW_LINE +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - some random" + NEW_LINE +
                        NEW_LINE +
                        "  argument_one" + NEW_LINE +
                        NEW_LINE +
                        " message" + NEW_LINE,
                    MediaType.PLAIN_TEXT_UTF_8).withStatusCode(200))
            );
    }

    @Test
    public void shouldRetrieveLogEntriesWithRequestMatcher() {
        // given
        configuration.logLevel(Level.INFO);
        httpState.log(
                new LogEntry()
                    .setLogLevel(INFO)
                    .setType(NO_MATCH_RESPONSE)
                    .setHttpRequest(request("request_one"))
                    .setExpectation(new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_two")))
                    .setMessageFormat("no expectation for:{}returning response:{}")
                    .setArguments(request("request_one"), notFoundResponse())
            );
            httpState.log(
                new LogEntry()
                    .setLogLevel(INFO)
                    .setType(EXPECTATION_RESPONSE)
                    .setHttpRequest(request("request_two"))
                    .setHttpResponse(response("response_two"))
                    .setMessageFormat("returning error:{}for request:{}for action:{}")
                    .setArguments(request("request_two"), response("response_two"), response("response_two"))
            );
            httpState.log(
                new LogEntry()
                    .setType(EXPECTATION_MATCHED)
                    .setLogLevel(INFO)
                    .setHttpRequest(request("request_one"))
                    .setExpectation(new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_two")))
                    .setMessageFormat("request:{}matched expectation:{}")
                    .setArguments(request("request_one"), new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_two")))
            );
            httpState.log(
                new LogEntry()
                    .setType(EXPECTATION_MATCHED)
                    .setLogLevel(INFO)
                    .setHttpRequest(request("request_two"))
                    .setExpectation(new Expectation(request("request_two")).withId("key_two").thenRespond(response("response_two")))
                    .setMessageFormat("request:{}matched expectation:{}")
                    .setArguments(request("request_two"), new Expectation(request("request_two")).withId("key_two").thenRespond(response("response_two")))
            );
            httpState.log(
                new LogEntry()
                    .setType(TRACE)
                    .setHttpRequest(request("request_four"))
                    .setExpectation(new Expectation(request("request_four")).withId("key_four").thenRespond(response("response_four")))
                    .setMessageFormat("some random{}message")
                    .setArguments("argument_one")
            );

            // when
            HttpResponse response = httpState
                .retrieve(
                    request()
                        .withQueryStringParameter("type", "logs")
                        .withBody(requestDefinitionSerializer.serialize(request("request_one")))
                );

            // then
            assertThat(response,
                is(response().withBody("" +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - no expectation for:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"path\" : \"request_one\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        " returning response:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"statusCode\" : 404," + NEW_LINE +
                        "    \"reasonPhrase\" : \"Not Found\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        "------------------------------------" + NEW_LINE +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - request:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"path\" : \"request_one\"" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE +
                        " matched expectation:" + NEW_LINE +
                        NEW_LINE +
                        "  {" + NEW_LINE +
                        "    \"httpRequest\" : {" + NEW_LINE +
                        "      \"path\" : \"request_one\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"httpResponse\" : {" + NEW_LINE +
                        "      \"statusCode\" : 200," + NEW_LINE +
                        "      \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                        "      \"body\" : \"response_two\"" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"id\" : \"key_one\"," + NEW_LINE +
                        "    \"priority\" : 0," + NEW_LINE +
                        "    \"timeToLive\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"times\" : {" + NEW_LINE +
                        "      \"unlimited\" : true" + NEW_LINE +
                        "    }" + NEW_LINE +
                        "  }" + NEW_LINE +
                        NEW_LINE,
                    MediaType.PLAIN_TEXT_UTF_8).withStatusCode(200))
            );
    }

    @Test
    public void shouldRetrieveMetrics() {
        // given
        Scheduler scheduler = mock(Scheduler.class);
        HttpState metricsEnabledState = new HttpState(configuration().metricsEnabled(true), new MockServerLogger(), scheduler);
        try {
            // when
            HttpResponse response = metricsEnabledState
                .retrieve(
                    request()
                        .withQueryStringParameter("type", "metrics")
                );

            // then
            assertThat(response.getStatusCode(), is(200));
            assertThat(response.getBodyAsString(), containsString("REQUESTS_RECEIVED_COUNT"));
            assertThat(response.getBodyAsString(), containsString("EXPECTATIONS_NOT_MATCHED_COUNT"));
        } finally {
            metricsEnabledState.stop();
        }
    }

    @Test
    public void shouldRetrieveEmptyMetricsWhenDisabled() {
        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "metrics")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("{}"));
    }

    @Test
    public void shouldThrowExceptionForInvalidRetrieveType() {
        try {
            // when
            httpState.retrieve(request().withQueryStringParameter("type", "invalid"));
            fail("expected exception to be thrown");
        } catch (Throwable throwable) {
            // then
            assertThat(throwable, instanceOf(IllegalArgumentException.class));
            assertThat(throwable.getMessage(), is("\"invalid\" is not a valid value for \"type\" parameter, only the following values are supported [logs, requests, request_responses, recorded_expectations, active_expectations, metrics]"));
        }
    }

    @Test
    public void shouldThrowExceptionForInvalidRetrieveFormat() {
        try {
            // when
            httpState.retrieve(request().withQueryStringParameter("format", "invalid"));
            fail("expected exception to be thrown");
        } catch (Throwable throwable) {
            // then
            assertThat(throwable, instanceOf(IllegalArgumentException.class));
            assertThat(throwable.getMessage(), is("\"invalid\" is not a valid value for \"format\" parameter, only the following values are supported [java, javascript, python, go, csharp, ruby, rust, php, json, log_entries, har, openapi, postman, bruno, curl]"));
        }
    }

    @Test
    public void shouldReset() {
        // given
        configuration.logLevel(Level.INFO);
        httpState.add(
                new Expectation(request("request_one"))
                    .thenRespond(response("response_one"))
            );
            // given - some log entries
            httpState.log(
                new LogEntry()
                    .setType(TRACE)
                    .setHttpRequest(request("request_four"))
                    .setExpectation(new Expectation(request("request_four")).thenRespond(response("response_four")))
                    .setMessageFormat("some random{}message")
                    .setArguments("argument_one")
            );

            // when
            httpState.reset();

            // then - retrieves correct state
            assertThat(
                httpState
                    .retrieve(
                        request()
                            .withQueryStringParameter("type", "logs")
                    ),
                is(response().withBody("" +
                        LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - resetting all expectations and request logs" + NEW_LINE,
                    MediaType.PLAIN_TEXT_UTF_8).withStatusCode(200))
            );
            assertThat(
                httpState
                    .retrieve(
                        request()
                            .withQueryStringParameter("type", "active_expectations")
                    ),
                is(response().withBody("[]", MediaType.JSON_UTF_8).withStatusCode(200))
            );
            assertThat(httpState.firstMatchingExpectation(request("request_one")), nullValue());
    }

    @Test
    public void shouldVerifyWithFuture() throws Exception {
        // given
        MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("two"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );

        // when
        String result = httpState.verify(
            new Verification()
                .withRequest(request("two"))
        ).get(5, SECONDS);

        // then
        assertThat(result, is(""));
    }

    @Test
    public void shouldVerifyWithCallback() throws Exception {
        // given
        MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("two"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        CompletableFuture<String> verificationResult = new CompletableFuture<>();

        // when
        httpState.verify(
            new Verification()
                .withRequest(request("two")),
            verificationResult::complete
        );

        // then
        assertThat(verificationResult.get(5, SECONDS), is(""));
    }

    @Test
    public void shouldVerifyFailureWithCallback() throws Exception {
        // given
        MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        CompletableFuture<String> verificationResult = new CompletableFuture<>();

        // when
        httpState.verify(
            new Verification()
                .withRequest(request("two")),
            verificationResult::complete
        );

        // then
        assertThat(verificationResult.get(5, SECONDS), is("Request not found at least once, expected:<{" + NEW_LINE +
            "  \"path\" : \"two\"" + NEW_LINE +
            "}> but was:<{" + NEW_LINE +
            "  \"path\" : \"one\"" + NEW_LINE +
            "}>"));
    }

    @Test
    public void shouldVerifySequenceWithFuture() throws Exception {
        // given
        MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("two"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );

        // when
        String result = httpState.verify(
            new VerificationSequence()
                .withRequests(
                    request("one"),
                    request("three")
                )
        ).get(5, SECONDS);

        // then
        assertThat(result, is(""));
    }

    @Test
    public void shouldVerifySequenceWithCallback() throws Exception {
        // given
        MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("two"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        CompletableFuture<String> verificationResult = new CompletableFuture<>();

        // when
        httpState.verify(
            new VerificationSequence()
                .withRequests(
                    request("one"),
                    request("three")
                ),
            verificationResult::complete
        );

        // then
        assertThat(verificationResult.get(5, SECONDS), is(""));
    }

    @Test
    public void shouldVerifySequenceFailureWithCallback() throws Exception {
        // given
        MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("two"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        CompletableFuture<String> verificationResult = new CompletableFuture<>();

        // when
        httpState.verify(
            new VerificationSequence()
                .withRequests(
                    request("three"),
                    request("one")
                ),
            verificationResult::complete
        );

        // then
        assertThat(verificationResult.get(5, SECONDS), is("Request sequence not found, expected:<[ {" + NEW_LINE +
            "  \"path\" : \"three\"" + NEW_LINE +
            "}, {" + NEW_LINE +
            "  \"path\" : \"one\"" + NEW_LINE +
            "} ]> but was:<[ {" + NEW_LINE +
            "  \"path\" : \"one\"" + NEW_LINE +
            "}, {" + NEW_LINE +
            "  \"path\" : \"two\"" + NEW_LINE +
            "}, {" + NEW_LINE +
            "  \"path\" : \"three\"" + NEW_LINE +
            "} ]>"));
    }

    @Test
    public void shouldRetrieveLogsAsLogEntries() {
        // given
        configuration.logLevel(Level.INFO);
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("request_one"))
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request("request_one"))
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(EXPECTATION_MATCHED)
                .setHttpRequest(request("request_one"))
                .setExpectation(new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_one")))
                .setMessageFormat("request:{}matched expectation:{}")
                .setArguments(request("request_one"), new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_one")))
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "logs")
                    .withQueryStringParameter("format", "log_entries")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        String body = response.getBodyAsString();
        assertThat(body, containsString("RECEIVED_REQUEST"));
        assertThat(body, containsString("EXPECTATION_MATCHED"));
        LogEntrySerializer serializer = new LogEntrySerializer(new MockServerLogger());
        LogEntry[] deserialized = serializer.deserializeArray(body);
        assertThat(deserialized.length, is(2));
        assertThat(deserialized[0].getType(), is(RECEIVED_REQUEST));
        assertThat(deserialized[1].getType(), is(EXPECTATION_MATCHED));
        assertThat(deserialized[0].getHttpRequest(), is(nullValue()));
        assertThat(deserialized[1].getExpectation(), is(nullValue()));
    }

    @Test
    public void shouldRetrieveLogsAsLogEntriesByCorrelationId() {
        // given
        configuration.logLevel(Level.INFO);
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(RECEIVED_REQUEST)
                .setCorrelationId("test-corr-id-1")
                .setHttpRequest(request("request_one"))
                .setMessageFormat("received request:{}")
                .setArguments("request_one")
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(EXPECTATION_MATCHED)
                .setCorrelationId("test-corr-id-1")
                .setHttpRequest(request("request_one"))
                .setMessageFormat("matched:{}")
                .setArguments("key_one")
        );
        httpState.log(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(RECEIVED_REQUEST)
                .setCorrelationId("test-corr-id-2")
                .setHttpRequest(request("request_two"))
                .setMessageFormat("received request:{}")
                .setArguments("request_two")
        );

        // when
        HttpResponse response = httpState
            .retrieve(
                request()
                    .withQueryStringParameter("type", "logs")
                    .withQueryStringParameter("format", "log_entries")
                    .withQueryStringParameter("correlationId", "test-corr-id-1")
            );

        // then
        assertThat(response.getStatusCode(), is(200));
        LogEntrySerializer serializer = new LogEntrySerializer(new MockServerLogger());
        LogEntry[] deserialized = serializer.deserializeArray(response.getBodyAsString());
        assertThat(deserialized.length, is(2));
        assertThat(deserialized[0].getType(), is(RECEIVED_REQUEST));
        assertThat(deserialized[0].getCorrelationId(), is("test-corr-id-1"));
        assertThat(deserialized[1].getType(), is(EXPECTATION_MATCHED));
        assertThat(deserialized[1].getCorrelationId(), is("test-corr-id-1"));
    }

    @Test
    public void shouldRoundTripLogEntriesThroughSerializer() {
        // given
        LogEntrySerializer serializer = new LogEntrySerializer(new MockServerLogger());
        LogEntry entry = new LogEntry()
            .setLogLevel(INFO)
            .setType(EXPECTATION_NOT_MATCHED)
            .setCorrelationId("round-trip-test")
            .setMessageFormat("test message:{}")
            .setArguments("arg1");

        // when
        String json = serializer.serialize(Collections.singletonList(entry));
        LogEntry[] deserialized = serializer.deserializeArray(json);

        // then
        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getType(), is(EXPECTATION_NOT_MATCHED));
        assertThat(deserialized[0].getCorrelationId(), is("round-trip-test"));
        assertThat(deserialized[0].getLogLevel(), is(INFO));
        assertThat(deserialized[0].getMessageFormat(), is("test message:{}"));
    }

    @Test
    public void shouldDeserializeEmptyLogEntryArray() {
        // given
        LogEntrySerializer serializer = new LogEntrySerializer(new MockServerLogger());

        // when / then
        assertThat(serializer.deserializeArray("[]").length, is(0));
        assertThat(serializer.deserializeArray("").length, is(0));
        assertThat(serializer.deserializeArray(null).length, is(0));
    }

    @Test
    public void shouldHandleFileStore() {
        // given
        HttpRequest storeRequest = request("/mockserver/files/store")
            .withMethod("PUT")
            .withBody("{\"name\":\"test.txt\",\"content\":\"hello world\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(storeRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        assertThat(responseWriter.response.getBodyAsString(), is("{\"name\":\"test.txt\",\"size\":11}"));
    }

    // --- WASM test endpoint (POST /mockserver/wasm/test) ---

    private static String matchRequestModuleBase64() throws java.io.IOException {
        try (java.io.InputStream in = HttpStateTest.class.getResourceAsStream("/org/mockserver/wasm/match-request.wasm")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }

    @Test
    public void shouldTestWasmModuleAndReturnMatchedTrue() throws java.io.IOException {
        // given
        boolean original = configuration.wasmEnabled();
        try {
            configuration.wasmEnabled(true);
            String body = "{\"module\":\"" + matchRequestModuleBase64() + "\","
                + "\"request\":{\"method\":\"POST\",\"path\":\"/orders\",\"headers\":{\"X-Tenant\":[\"acme\"]},\"body\":\"{}\"}}";
            HttpRequest testRequest = request("/mockserver/wasm/test").withMethod("POST").withBody(body);
            FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            boolean handle = httpState.handle(testRequest, responseWriter, false);

            // then
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(200));
            assertThat(responseWriter.response.getBodyAsString(), is("{\"matched\":true}"));
        } finally {
            configuration.wasmEnabled(original);
        }
    }

    @Test
    public void shouldTestWasmModuleAndReturnMatchedFalse() throws java.io.IOException {
        // given
        boolean original = configuration.wasmEnabled();
        try {
            configuration.wasmEnabled(true);
            String body = "{\"module\":\"" + matchRequestModuleBase64() + "\","
                + "\"request\":{\"method\":\"GET\",\"path\":\"/orders\",\"headers\":{\"X-Tenant\":[\"acme\"]},\"body\":\"{}\"}}";
            HttpRequest testRequest = request("/mockserver/wasm/test").withMethod("POST").withBody(body);
            FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            boolean handle = httpState.handle(testRequest, responseWriter, false);

            // then
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(200));
            assertThat(responseWriter.response.getBodyAsString(), is("{\"matched\":false}"));
        } finally {
            configuration.wasmEnabled(original);
        }
    }

    private static String matchRequestV2ModuleBase64() throws java.io.IOException {
        try (java.io.InputStream in = HttpStateTest.class.getResourceAsStream("/org/mockserver/wasm/match-request-v2.wasm")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }

    @Test
    public void shouldTestWasmV2ModuleWithQueryParametersAndCookies() throws java.io.IOException {
        // given — the v2 module matches POST /orders with query tenant=acme and cookie session=abc123
        boolean original = configuration.wasmEnabled();
        try {
            configuration.wasmEnabled(true);
            String body = "{\"module\":\"" + matchRequestV2ModuleBase64() + "\","
                + "\"request\":{\"method\":\"POST\",\"path\":\"/orders\","
                + "\"queryStringParameters\":{\"tenant\":[\"acme\"]},"
                + "\"cookies\":{\"session\":\"abc123\"},\"body\":\"{}\"}}";
            HttpRequest testRequest = request("/mockserver/wasm/test").withMethod("POST").withBody(body);
            FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            boolean handle = httpState.handle(testRequest, responseWriter, false);

            // then
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(200));
            assertThat(responseWriter.response.getBodyAsString(), is("{\"matched\":true}"));
        } finally {
            configuration.wasmEnabled(original);
        }
    }

    @Test
    public void shouldNotMatchWasmV2ModuleWhenCookieMissingFromSampleRequest() throws java.io.IOException {
        // given — same module, but the sample request omits the required cookie
        boolean original = configuration.wasmEnabled();
        try {
            configuration.wasmEnabled(true);
            String body = "{\"module\":\"" + matchRequestV2ModuleBase64() + "\","
                + "\"request\":{\"method\":\"POST\",\"path\":\"/orders\","
                + "\"queryStringParameters\":{\"tenant\":[\"acme\"]},\"body\":\"{}\"}}";
            HttpRequest testRequest = request("/mockserver/wasm/test").withMethod("POST").withBody(body);
            FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            boolean handle = httpState.handle(testRequest, responseWriter, false);

            // then
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(200));
            assertThat(responseWriter.response.getBodyAsString(), is("{\"matched\":false}"));
        } finally {
            configuration.wasmEnabled(original);
        }
    }

    @Test
    public void shouldReturnMatchedFalseForInvalidWasmModule() {
        // given
        boolean original = configuration.wasmEnabled();
        try {
            configuration.wasmEnabled(true);
            String junk = java.util.Base64.getEncoder().encodeToString(new byte[]{0x00, 0x01, 0x02, 0x03});
            String body = "{\"module\":\"" + junk + "\",\"request\":{\"method\":\"POST\",\"path\":\"/orders\"}}";
            HttpRequest testRequest = request("/mockserver/wasm/test").withMethod("POST").withBody(body);
            FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            boolean handle = httpState.handle(testRequest, responseWriter, false);

            // then — fails closed, no error
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(200));
            assertThat(responseWriter.response.getBodyAsString(), is("{\"matched\":false}"));
        } finally {
            configuration.wasmEnabled(original);
        }
    }

    @Test
    public void shouldRejectWasmTestWhenWasmDisabled() throws java.io.IOException {
        // given
        boolean original = configuration.wasmEnabled();
        try {
            configuration.wasmEnabled(false);
            String body = "{\"module\":\"" + matchRequestModuleBase64() + "\"}";
            HttpRequest testRequest = request("/mockserver/wasm/test").withMethod("POST").withBody(body);
            FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            boolean handle = httpState.handle(testRequest, responseWriter, false);

            // then
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(403));
        } finally {
            configuration.wasmEnabled(original);
        }
    }

    @Test
    public void shouldRejectWasmTestWithoutModule() {
        // given
        boolean original = configuration.wasmEnabled();
        try {
            configuration.wasmEnabled(true);
            HttpRequest testRequest = request("/mockserver/wasm/test").withMethod("POST")
                .withBody("{\"request\":{\"method\":\"POST\"}}");
            FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            boolean handle = httpState.handle(testRequest, responseWriter, false);

            // then
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(400));
        } finally {
            configuration.wasmEnabled(original);
        }
    }

    private static String shapeResponseModuleBase64() throws java.io.IOException {
        try (java.io.InputStream in = HttpStateTest.class.getResourceAsStream("/org/mockserver/wasm/shape-response.wasm")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }

    @Test
    public void shouldTestWasmModuleAndReturnShapedResponseWhenCandidateProvided() throws java.io.IOException {
        // given — the shape-response module matches POST /shape and, given a candidate response, shapes it
        boolean original = configuration.wasmEnabled();
        try {
            configuration.wasmEnabled(true);
            String body = "{\"module\":\"" + shapeResponseModuleBase64() + "\","
                + "\"request\":{\"method\":\"POST\",\"path\":\"/shape\",\"body\":\"{}\"},"
                + "\"response\":{\"statusCode\":201,\"headers\":{\"Content-Type\":[\"application/json\"]},\"body\":\"{\\\"name\\\":\\\"acme\\\"}\"}}";
            HttpRequest testRequest = request("/mockserver/wasm/test").withMethod("POST").withBody(body);
            FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            boolean handle = httpState.handle(testRequest, responseWriter, false);

            // then — matched true and the shaped response is returned
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(200));
            com.fasterxml.jackson.databind.JsonNode result = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                .readTree(responseWriter.response.getBodyAsString());
            assertThat(result.get("matched").asBoolean(), is(true));
            com.fasterxml.jackson.databind.JsonNode shaped = result.get("shaped");
            assertThat(shaped, is(notNullValue()));
            assertThat(shaped.get("statusCode").asInt(), is(200));
            assertThat(shaped.get("headers").get("X-Shaped").get(0).asText(), is("true"));
            assertThat(shaped.get("body").asText(), is("{\"greeting\":\"Hello, acme!\",\"shaped\":true}"));
        } finally {
            configuration.wasmEnabled(original);
        }
    }

    @Test
    public void shouldTestWasmModuleWithNullShapedWhenModuleDoesNotShape() throws java.io.IOException {
        // given — a pure predicate module (no shape_response) with a candidate response present
        boolean original = configuration.wasmEnabled();
        try {
            configuration.wasmEnabled(true);
            String body = "{\"module\":\"" + matchRequestModuleBase64() + "\","
                + "\"request\":{\"method\":\"POST\",\"path\":\"/orders\",\"headers\":{\"X-Tenant\":[\"acme\"]}},"
                + "\"response\":{\"statusCode\":200,\"body\":\"{}\"}}";
            HttpRequest testRequest = request("/mockserver/wasm/test").withMethod("POST").withBody(body);
            FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            boolean handle = httpState.handle(testRequest, responseWriter, false);

            // then — matched true, shaped is explicit null (module does not shape)
            assertThat(handle, is(true));
            com.fasterxml.jackson.databind.JsonNode result = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                .readTree(responseWriter.response.getBodyAsString());
            assertThat(result.get("matched").asBoolean(), is(true));
            assertThat(result.has("shaped"), is(true));
            assertThat(result.get("shaped").isNull(), is(true));
        } finally {
            configuration.wasmEnabled(original);
        }
    }

    @Test
    public void shouldTestLoadedWasmModuleByName() throws java.io.IOException {
        // given
        boolean original = configuration.wasmEnabled();
        try {
            configuration.wasmEnabled(true);
            byte[] moduleBytes = java.util.Base64.getDecoder().decode(matchRequestModuleBase64());
            org.mockserver.wasm.WasmStore.getInstance().put("ordersByName", moduleBytes);
            String body = "{\"moduleName\":\"ordersByName\","
                + "\"request\":{\"method\":\"POST\",\"path\":\"/orders\",\"headers\":{\"X-Tenant\":[\"acme\"]}}}";
            HttpRequest testRequest = request("/mockserver/wasm/test").withMethod("POST").withBody(body);
            FakeResponseWriter responseWriter = new FakeResponseWriter();

            // when
            boolean handle = httpState.handle(testRequest, responseWriter, false);

            // then
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(200));
            assertThat(responseWriter.response.getBodyAsString(), is("{\"matched\":true}"));
        } finally {
            org.mockserver.wasm.WasmStore.getInstance().reset();
            configuration.wasmEnabled(original);
        }
    }

    @Test
    public void shouldHandleFileStoreBase64() {
        // given
        String base64Content = java.util.Base64.getEncoder().encodeToString("binary data".getBytes(UTF_8));
        HttpRequest storeRequest = request("/mockserver/files/store")
            .withMethod("PUT")
            .withBody("{\"name\":\"binary.dat\",\"content\":\"" + base64Content + "\",\"base64\":true}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(storeRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        assertThat(responseWriter.response.getBodyAsString(), is("{\"name\":\"binary.dat\",\"size\":11}"));
    }

    @Test
    public void shouldHandleFileStoreWithEmptyBody() {
        // given
        HttpRequest storeRequest = request("/mockserver/files/store")
            .withMethod("PUT");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(storeRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    @Test
    public void shouldHandleFileStoreMissingFields() {
        // given
        HttpRequest storeRequest = request("/mockserver/files/store")
            .withMethod("PUT")
            .withBody("{\"name\":\"test.txt\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(storeRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    @Test
    public void shouldHandleFileRetrieve() {
        // given
        httpState.getFileStore().store("test.txt", "hello world".getBytes(UTF_8));
        HttpRequest retrieveRequest = request("/mockserver/files/retrieve")
            .withMethod("PUT")
            .withBody("{\"name\":\"test.txt\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(new String(responseWriter.response.getBodyAsRawBytes(), UTF_8), is("hello world"));
    }

    @Test
    public void shouldHandleFileRetrieveNotFound() {
        // given
        HttpRequest retrieveRequest = request("/mockserver/files/retrieve")
            .withMethod("PUT")
            .withBody("{\"name\":\"nonexistent.txt\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(404));
    }

    @Test
    public void shouldHandleFileRetrieveMissingName() {
        // given
        HttpRequest retrieveRequest = request("/mockserver/files/retrieve")
            .withMethod("PUT")
            .withBody("{\"other\":\"value\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    @Test
    public void shouldHandleFileList() {
        // given
        httpState.getFileStore().store("file1.txt", "data1".getBytes(UTF_8));
        httpState.getFileStore().store("file2.txt", "data2".getBytes(UTF_8));
        HttpRequest listRequest = request("/mockserver/files/list")
            .withMethod("PUT");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(listRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("file1.txt"));
        assertThat(body, containsString("file2.txt"));
    }

    @Test
    public void shouldHandleFileListEmpty() {
        // given
        HttpRequest listRequest = request("/mockserver/files/list")
            .withMethod("PUT");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(listRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("[ ]"));
    }

    @Test
    public void shouldHandleFileDelete() {
        // given
        httpState.getFileStore().store("test.txt", "data".getBytes(UTF_8));
        HttpRequest deleteRequest = request("/mockserver/files/delete")
            .withMethod("PUT")
            .withBody("{\"name\":\"test.txt\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(deleteRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(httpState.getFileStore().exists("test.txt"), is(false));
    }

    @Test
    public void shouldHandleFileDeleteNotFound() {
        // given
        HttpRequest deleteRequest = request("/mockserver/files/delete")
            .withMethod("PUT")
            .withBody("{\"name\":\"nonexistent.txt\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(deleteRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(404));
    }

    @Test
    public void shouldHandleFileDeleteMissingName() {
        // given
        HttpRequest deleteRequest = request("/mockserver/files/delete")
            .withMethod("PUT")
            .withBody("{\"other\":\"value\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(deleteRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    @Test
    public void shouldReturnEarlyMatchingExpectationWhenRespondBeforeBodyIsTrue() {
        // given
        Expectation expectation = new Expectation(
            request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true)
        ).thenRespond(response().withStatusCode(403));
        httpState.add(expectation);

        // when
        Expectation matched = httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/upload")
        );

        // then
        assertThat(matched, is(expectation));
    }

    @Test
    public void shouldNotEarlyMatchControlPlaneRequestsAgainstCatchAllRespondBeforeBody() {
        // given a catch-all respondBeforeBody expectation (e.g. seeded via an initialization file)
        httpState.add(new Expectation(
            request().withRespondBeforeBody(true)
        ).thenRespond(response().withStatusCode(404)));

        // when control-plane requests are checked for an early match
        // then they are never hijacked by the data-plane early expectation — they fall through
        // to the standard pipeline so HttpState.handle() dispatches the control plane
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("PUT").withPath("/mockserver/reset")
        ), is(nullValue()));
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("PUT").withPath("/mockserver/status")
        ), is(nullValue()));

        // and a genuine data-plane request still early-matches the catch-all
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("GET").withPath("/some/data/plane/path")
        ), is(notNullValue()));
    }

    @Test
    public void shouldReturnNullEarlyMatchingExpectationWhenRespondBeforeBodyIsNotSet() {
        // given
        httpState.add(new Expectation(
            request().withMethod("POST").withPath("/upload")
        ).thenRespond(response().withStatusCode(200)));

        // when
        Expectation matched = httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/upload")
        );

        // then
        assertThat(matched, is(nullValue()));
    }

    @Test
    public void shouldRejectRespondBeforeBodyWithBodyMatcher() {
        // given
        Expectation expectation = new Expectation(
            request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true).withBody("some-body")
        ).thenRespond(response().withStatusCode(403));

        // when / then
        try {
            httpState.add(expectation);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage(), CoreMatchers.containsString("respondBeforeBody"));
            assertThat(expected.getMessage(), CoreMatchers.containsString("body matcher"));
        }
    }

    @Test
    public void shouldRejectRespondBeforeBodyWithNullAction() {
        // given an Expectation with respondBeforeBody=true but no configured action
        Expectation expectation = new Expectation(
            request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true)
        );

        // when / then
        try {
            httpState.add(expectation);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage(), CoreMatchers.containsString("respondBeforeBody"));
            assertThat(expected.getMessage(), CoreMatchers.containsString("RESPONSE or ERROR action"));
        }
    }

    @Test
    public void shouldRejectRespondBeforeBodyWithForwardAction() {
        // given
        Expectation expectation = new Expectation(
            request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true)
        ).thenForward(org.mockserver.model.HttpForward.forward().withHost("example.com").withPort(80));

        // when / then
        try {
            httpState.add(expectation);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage(), CoreMatchers.containsString("respondBeforeBody"));
            assertThat(expected.getMessage(), CoreMatchers.containsString("RESPONSE"));
        }
    }

    @Test
    public void shouldAllowRespondBeforeBodyWithErrorAction() {
        // given
        Expectation expectation = new Expectation(
            request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true)
        ).thenError(org.mockserver.model.HttpError.error().withDropConnection(true));

        // when (no exception)
        httpState.add(expectation);

        // then
        Expectation matched = httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/upload")
        );
        assertThat(matched, is(expectation));
    }

    // --- respondBeforeBody fast-path (empty-set skip) behavioural tests ---
    // These exercise the id-keyed set that gates firstMatchingEarlyExpectation: when no expectation
    // carries respondBeforeBody the scan is skipped, and the set must stay in step with the store
    // across add / update / remove / reset / eviction so the feature never silently stops working.

    @Test
    public void shouldEarlyMatchRespondBeforeBodyAmongManyOrdinaryExpectations() {
        // given many ordinary expectations plus one respondBeforeBody expectation
        for (int i = 0; i < 50; i++) {
            httpState.add(new Expectation(
                request().withMethod("GET").withPath("/ordinary/" + i)
            ).thenRespond(response().withStatusCode(200)));
        }
        Expectation early = new Expectation(
            request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true)
        ).thenRespond(response().withStatusCode(403));
        httpState.add(early);

        // then the respondBeforeBody expectation is found by the early matcher
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/upload")
        ), is(early));
        // and an ordinary request (no respondBeforeBody expectation matches it) is not early-matched
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("GET").withPath("/ordinary/7")
        ), is(nullValue()));
    }

    @Test
    public void shouldEarlyMatchRespondBeforeBodyAfterStoreChurn() {
        // given a respondBeforeBody expectation surrounded by churn (adds, updates and removes)
        Expectation early = new Expectation(
            request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true)
        ).thenRespond(response().withStatusCode(403));
        httpState.add(early);
        for (int i = 0; i < 20; i++) {
            httpState.add(new Expectation(
                request().withMethod("GET").withPath("/churn/" + i)
            ).thenRespond(response().withStatusCode(200)));
        }
        for (int i = 0; i < 10; i++) {
            // clear a specific expectation: the matcher to clear is carried in the request BODY
            httpState.clear(request().withMethod("PUT").withBody(
                requestDefinitionSerializer.serialize(request().withMethod("GET").withPath("/churn/" + i))
            ));
        }
        for (int i = 10; i < 20; i++) {
            // re-add (churn) around the early expectation
            httpState.add(new Expectation(
                request().withMethod("GET").withPath("/churn/" + i)
            ).thenRespond(response().withStatusCode(201)));
        }

        // then the respondBeforeBody expectation still early-matches through all the churn
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/upload")
        ), is(early));

        // and once it is removed the early matcher no longer matches its path
        httpState.clear(request().withMethod("PUT").withBody(
            requestDefinitionSerializer.serialize(request().withMethod("POST").withPath("/upload"))
        ));
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/upload")
        ), is(nullValue()));
    }

    @Test
    public void shouldTrackRespondBeforeBodyAcrossInPlaceUpdateFlagFlips() {
        // given an in-place-updatable expectation (fixed id) that starts with respondBeforeBody=true
        String id = "flip-expectation-id";
        httpState.add(new Expectation(
            request().withMethod("POST").withPath("/flip").withRespondBeforeBody(true)
        ).withId(id).thenRespond(response().withStatusCode(403)));
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/flip")
        ), is(notNullValue()));

        // when the same id is updated in place to turn respondBeforeBody OFF
        httpState.add(new Expectation(
            request().withMethod("POST").withPath("/flip")
        ).withId(id).thenRespond(response().withStatusCode(200)));

        // then it no longer early-matches
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/flip")
        ), is(nullValue()));

        // when the same id is updated in place to turn respondBeforeBody back ON
        httpState.add(new Expectation(
            request().withMethod("POST").withPath("/flip").withRespondBeforeBody(true)
        ).withId(id).thenRespond(response().withStatusCode(403)));

        // then it early-matches again
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/flip")
        ), is(notNullValue()));
    }

    @Test
    public void shouldKeepRespondBeforeBodyTrackingConsistentAcrossReset() {
        // given a respondBeforeBody expectation that early-matches
        httpState.add(new Expectation(
            request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true)
        ).thenRespond(response().withStatusCode(403)));
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/upload")
        ), is(notNullValue()));

        // when the store is reset
        httpState.reset();

        // then the early matcher no longer matches its former path
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/upload")
        ), is(nullValue()));

        // and a freshly-added respondBeforeBody expectation early-matches again (set works post-reset)
        Expectation afterReset = new Expectation(
            request().withMethod("POST").withPath("/again").withRespondBeforeBody(true)
        ).thenRespond(response().withStatusCode(403));
        httpState.add(afterReset);
        assertThat(httpState.firstMatchingEarlyExpectation(
            request().withMethod("POST").withPath("/again")
        ), is(afterReset));
    }

    @Test
    public void shouldKnowWhetherAnyExpectationMatchesBinaryRequests() {
        String id = "binary-expectation-id";
        assertThat(httpState.hasBinaryExpectations(), is(false));

        httpState.add(new Expectation(request().withPath("/http")).thenRespond(response()));
        assertThat("an HTTP expectation is not one", httpState.hasBinaryExpectations(), is(false));

        httpState.add(new Expectation(BinaryRequestDefinition.binaryRequest(new byte[]{1, 2})).withId(id).thenRespondWithBinary(BinaryResponse.binaryResponse(new byte[]{3})));
        assertThat(httpState.hasBinaryExpectations(), is(true));

        httpState.add(new Expectation(request().withPath("/now-http")).withId(id).thenRespond(response()));
        assertThat("updated in place to an HTTP request", httpState.hasBinaryExpectations(), is(false));

        httpState.add(new Expectation(BinaryRequestDefinition.binaryRequest(new byte[]{1, 2})).withId(id).thenRespondWithBinary(BinaryResponse.binaryResponse(new byte[]{3})));
        assertThat("updated in place back to a binary request", httpState.hasBinaryExpectations(), is(true));

        httpState.reset();
        assertThat(httpState.hasBinaryExpectations(), is(false));
    }

    @Test
    public void shouldRemoveABinaryExpectationUsedUpByTimesOncePostProcessed() {
        httpState.add(new Expectation(BinaryRequestDefinition.binaryRequest(new byte[]{1, 2}), Times.once(), TimeToLive.unlimited(), 0)
            .thenRespondWithBinary(BinaryResponse.binaryResponse(new byte[]{3})));

        Expectation matched = httpState.firstMatchingExpectation(BinaryRequestDefinition.binaryRequest(new byte[]{1, 2}));
        httpState.postProcess(matched);

        assertThat(matched, is(notNullValue()));
        assertThat(httpState.hasBinaryExpectations(), is(false));
    }

    @Test
    public void shouldKeepRespondBeforeBodyTrackingConsistentAcrossEviction() {
        // given a store capped at maxExpectations=3
        Scheduler evictionScheduler = mock(Scheduler.class);
        HttpState smallState = new HttpState(
            configuration().maxExpectations(3),
            new MockServerLogger(configuration, MockServerLogger.class),
            evictionScheduler
        );
        try {
            // and a respondBeforeBody expectation added FIRST (so it is the oldest / first evicted)
            smallState.add(new Expectation(
                request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true)
            ).thenRespond(response().withStatusCode(403)));
            assertThat(smallState.firstMatchingEarlyExpectation(
                request().withMethod("POST").withPath("/upload")
            ), is(notNullValue()));

            // when enough ordinary expectations are added to overflow the cap and evict the oldest
            for (int i = 0; i < 3; i++) {
                smallState.add(new Expectation(
                    request().withMethod("GET").withPath("/evict/" + i)
                ).thenRespond(response().withStatusCode(200)));
            }

            // then the evicted respondBeforeBody expectation no longer early-matches
            assertThat(smallState.firstMatchingEarlyExpectation(
                request().withMethod("POST").withPath("/upload")
            ), is(nullValue()));

            // and a fresh respondBeforeBody expectation still early-matches (set consistent after eviction)
            Expectation afterEviction = new Expectation(
                request().withMethod("POST").withPath("/fresh").withRespondBeforeBody(true)
            ).thenRespond(response().withStatusCode(403));
            smallState.add(afterEviction);
            assertThat(smallState.firstMatchingEarlyExpectation(
                request().withMethod("POST").withPath("/fresh")
            ), is(afterEviction));
        } finally {
            smallState.stop();
        }
    }

    @Test
    public void shouldResetFileStore() {
        // given
        httpState.getFileStore().store("test.txt", "data".getBytes(UTF_8));

        // when
        httpState.reset();

        // then
        assertThat(httpState.getFileStore().size(), is(0));
    }

    // --- Effective-configuration endpoint test (GET /mockserver/config) ---

    @Test
    public void shouldReturnEffectiveConfigurationAsJsonWithSourcesAndRedaction() throws Exception {
        // given — clear any cached maxExpectations so the system-property tier is authoritative
        // (effectiveConfiguration is cache-first, exactly like the real resolution path).
        String previousNonSensitive = System.getProperty("mockserver.maxExpectations");
        String previousSensitive = System.getProperty("mockserver.llmApiKey");
        java.lang.reflect.Field cacheField = org.mockserver.configuration.ConfigurationProperties.class.getDeclaredField("propertyCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> cache = (java.util.Map<String, String>) cacheField.get(null);
        String previousCachedMaxExpectations = cache != null ? cache.get("mockserver.maxExpectations") : null;
        try {
            if (cache != null) {
                cache.remove("mockserver.maxExpectations");
            }
            System.setProperty("mockserver.maxExpectations", "4242");
            System.setProperty("mockserver.llmApiKey", "super-secret-endpoint-value");

            FakeResponseWriter responseWriter = new FakeResponseWriter();
            HttpRequest configRequest = request("/mockserver/config").withMethod("GET");

            // when
            boolean handle = httpState.handle(configRequest, responseWriter, false);

            // then
            assertThat(handle, is(true));
            assertThat(responseWriter.response.getStatusCode(), is(200));
            String body = responseWriter.response.getBodyAsString();
            assertThat(body, containsString("\"name\":\"mockserver.maxExpectations\""));
            assertThat(body, containsString("\"value\":\"4242\""));
            assertThat(body, containsString("\"source\":\"system-property\""));
            // sensitive value redacted, never printed verbatim
            assertThat(body, containsString("\"name\":\"mockserver.llmApiKey\""));
            assertThat(body, containsString("\"value\":\"***REDACTED***\""));
            assertThat(body, not(containsString("super-secret-endpoint-value")));
        } finally {
            if (cache != null) {
                if (previousCachedMaxExpectations != null) {
                    cache.put("mockserver.maxExpectations", previousCachedMaxExpectations);
                } else {
                    cache.remove("mockserver.maxExpectations");
                }
            }
            if (previousNonSensitive != null) {
                System.setProperty("mockserver.maxExpectations", previousNonSensitive);
            } else {
                System.clearProperty("mockserver.maxExpectations");
            }
            if (previousSensitive != null) {
                System.setProperty("mockserver.llmApiKey", previousSensitive);
            } else {
                System.clearProperty("mockserver.llmApiKey");
            }
        }
    }

    // --- Clock endpoint tests ---

    @Test
    public void shouldHandleClockFreezeWithInstant() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clockRequest = request("/mockserver/clock")
            .withMethod("PUT")
            .withBody("{\"action\":\"freeze\",\"instant\":\"2024-01-01T00:00:00Z\"}");

        // when
        boolean handle = httpState.handle(clockRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"status\" : \"freeze\""));
        assertThat(body, containsString("\"currentInstant\" : \"2024-01-01T00:00:00Z\""));
        assertThat(body, containsString("\"currentEpochMillis\""));
    }

    @Test
    public void shouldHandleClockFreezeWithoutInstant() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clockRequest = request("/mockserver/clock")
            .withMethod("PUT")
            .withBody("{\"action\":\"freeze\"}");

        // when
        boolean handle = httpState.handle(clockRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"status\" : \"freeze\""));
        assertThat(body, containsString("\"currentInstant\""));
    }

    @Test
    public void shouldHandleClockAdvance() throws Exception {
        // given
        TimeService.freeze(java.time.Instant.parse("2024-01-01T00:00:00Z"));
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clockRequest = request("/mockserver/clock")
            .withMethod("PUT")
            .withBody("{\"action\":\"advance\",\"durationMillis\":60000}");

        // when
        boolean handle = httpState.handle(clockRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"status\" : \"advance\""));
        assertThat(body, containsString("\"currentInstant\" : \"2024-01-01T00:01:00Z\""));
    }

    @Test
    public void shouldHandleClockReset() throws Exception {
        // given
        TimeService.freeze(java.time.Instant.parse("2024-01-01T00:00:00Z"));
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clockRequest = request("/mockserver/clock")
            .withMethod("PUT")
            .withBody("{\"action\":\"reset\"}");

        // when
        boolean handle = httpState.handle(clockRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"status\" : \"reset\""));
        assertThat(TimeService.isFrozen(), is(false));
    }

    @Test
    public void shouldRejectClockWithUnknownAction() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clockRequest = request("/mockserver/clock")
            .withMethod("PUT")
            .withBody("{\"action\":\"rewind\"}");

        // when
        boolean handle = httpState.handle(clockRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("unknown action 'rewind'"));
    }

    @Test
    public void shouldRejectClockWithMissingAction() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clockRequest = request("/mockserver/clock")
            .withMethod("PUT")
            .withBody("{\"instant\":\"2024-01-01T00:00:00Z\"}");

        // when
        boolean handle = httpState.handle(clockRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("'action' field is required"));
    }

    @Test
    public void shouldRejectClockAdvanceWithNonPositiveDuration() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clockRequest = request("/mockserver/clock")
            .withMethod("PUT")
            .withBody("{\"action\":\"advance\",\"durationMillis\":0}");

        // when
        boolean handle = httpState.handle(clockRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("'durationMillis' must be a positive number"));
    }

    @Test
    public void shouldRejectClockFreezeWithInvalidInstant() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clockRequest = request("/mockserver/clock")
            .withMethod("PUT")
            .withBody("{\"action\":\"freeze\",\"instant\":\"not-a-date\"}");

        // when
        boolean handle = httpState.handle(clockRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("invalid 'instant' value"));
    }

    @Test
    public void shouldHandleClockGetWhenUnfrozen() throws Exception {
        // given
        TimeService.reset();
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clockRequest = request("/mockserver/clock")
            .withMethod("GET");

        // when
        boolean handle = httpState.handle(clockRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"frozen\" : false"));
        assertThat(body, containsString("\"currentInstant\""));
        assertThat(body, containsString("\"currentEpochMillis\""));
    }

    @Test
    public void shouldHandleClockGetWhenFrozen() throws Exception {
        // given
        TimeService.freeze(java.time.Instant.parse("2024-06-15T12:00:00Z"));
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clockRequest = request("/mockserver/clock")
            .withMethod("GET");

        // when
        boolean handle = httpState.handle(clockRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"frozen\" : true"));
        assertThat(body, containsString("\"currentInstant\" : \"2024-06-15T12:00:00Z\""));
    }

    // --- Service chaos endpoint tests ---

    @Test
    public void shouldRegisterAndGetServiceChaos() throws Exception {
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
        // register
        FakeResponseWriter putWriter = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/serviceChaos")
            .withMethod("PUT")
            .withBody("{\"host\":\"upstream.svc\",\"chaos\":{\"errorStatus\":503,\"errorProbability\":1.0}}");
        assertThat(httpState.handle(putRequest, putWriter, false), is(true));
        assertThat(putWriter.response.getStatusCode(), is(200));
        assertThat(putWriter.response.getBodyAsString(), containsString("\"status\" : \"registered\""));
        assertThat(org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().get("upstream.svc"), is(notNullValue()));

        // read back
        FakeResponseWriter getWriter = new FakeResponseWriter();
        assertThat(httpState.handle(request("/mockserver/serviceChaos").withMethod("GET"), getWriter, false), is(true));
        assertThat(getWriter.response.getStatusCode(), is(200));
        String getBody = getWriter.response.getBodyAsString();
        assertThat(getBody, containsString("upstream.svc"));
        assertThat(getBody, containsString("\"errorStatus\""));
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
    }

    @Test
    public void shouldRemoveServiceChaosForHost() throws Exception {
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().put("upstream.svc",
            org.mockserver.model.HttpChaosProfile.httpChaosProfile().withErrorStatus(503));
        FakeResponseWriter writer = new FakeResponseWriter();
        HttpRequest removeRequest = request("/mockserver/serviceChaos")
            .withMethod("PUT")
            .withBody("{\"host\":\"upstream.svc\",\"remove\":true}");
        assertThat(httpState.handle(removeRequest, writer, false), is(true));
        assertThat(writer.response.getStatusCode(), is(200));
        assertThat(writer.response.getBodyAsString(), containsString("\"status\" : \"removed\""));
        assertThat(org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().get("upstream.svc"), is(nullValue()));
    }

    @Test
    public void shouldClearAllServiceChaos() throws Exception {
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().put("a",
            org.mockserver.model.HttpChaosProfile.httpChaosProfile().withErrorStatus(503));
        FakeResponseWriter writer = new FakeResponseWriter();
        HttpRequest clearRequest = request("/mockserver/serviceChaos")
            .withMethod("PUT")
            .withBody("{\"clear\":true}");
        assertThat(httpState.handle(clearRequest, writer, false), is(true));
        assertThat(writer.response.getStatusCode(), is(200));
        assertThat(writer.response.getBodyAsString(), containsString("\"status\" : \"cleared\""));
        assertThat(org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }

    @Test
    public void shouldRejectInvalidServiceChaosProfile() throws Exception {
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
        FakeResponseWriter writer = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/serviceChaos")
            .withMethod("PUT")
            .withBody("{\"host\":\"upstream.svc\",\"chaos\":{\"errorStatus\":999}}");
        assertThat(httpState.handle(putRequest, writer, false), is(true));
        assertThat(writer.response.getStatusCode(), is(400));
        assertThat(writer.response.getBodyAsString(), containsString("invalid chaos profile"));
        assertThat(org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().get("upstream.svc"), is(nullValue()));
    }

    @Test
    public void shouldRejectServiceChaosWithBothClearAndHost() throws Exception {
        FakeResponseWriter writer = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/serviceChaos")
            .withMethod("PUT")
            .withBody("{\"clear\":true,\"host\":\"upstream.svc\"}");
        assertThat(httpState.handle(putRequest, writer, false), is(true));
        assertThat(writer.response.getStatusCode(), is(400));
        assertThat(writer.response.getBodyAsString(), containsString("cannot specify both 'clear' and 'host'"));
    }

    @Test
    public void shouldRejectServiceChaosWithoutHost() throws Exception {
        FakeResponseWriter writer = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/serviceChaos")
            .withMethod("PUT")
            .withBody("{\"chaos\":{\"errorStatus\":503}}");
        assertThat(httpState.handle(putRequest, writer, false), is(true));
        assertThat(writer.response.getStatusCode(), is(400));
        assertThat(writer.response.getBodyAsString(), containsString("'host' field is required"));
    }

    @Test
    public void shouldRegisterServiceChaosWithTtl() throws Exception {
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
        FakeResponseWriter writer = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/serviceChaos")
            .withMethod("PUT")
            .withBody("{\"host\":\"upstream.svc\",\"chaos\":{\"errorStatus\":503},\"ttlMillis\":300000}");
        assertThat(httpState.handle(putRequest, writer, false), is(true));
        assertThat(writer.response.getStatusCode(), is(200));
        String body = writer.response.getBodyAsString();
        assertThat(body, containsString("\"status\" : \"registered\""));
        assertThat(body, containsString("\"ttlMillis\" : 300000"));
        assertThat(org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().get("upstream.svc"), is(notNullValue()));

        // GET surfaces a remaining-TTL countdown for the registration
        FakeResponseWriter getWriter = new FakeResponseWriter();
        assertThat(httpState.handle(request("/mockserver/serviceChaos").withMethod("GET"), getWriter, false), is(true));
        assertThat(getWriter.response.getBodyAsString(), containsString("ttlRemainingMillis"));

        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
    }

    @Test
    public void shouldAddCorsHeadersToServiceChaosResponsesWithoutEnableCorsForApi() {
        // the dashboard may be served from another origin (e.g. the UI dev server) — the
        // service-chaos control-plane responses must carry CORS headers even when
        // enableCORSForAPI is off (the default here), mirroring the metrics / MCP endpoints
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();

        FakeResponseWriter getWriter = new FakeResponseWriter();
        HttpRequest getRequest = request("/mockserver/serviceChaos")
            .withMethod("GET")
            .withHeader("Origin", "http://localhost:3000");
        assertThat(httpState.handle(getRequest, getWriter, false), is(true));
        assertThat(getWriter.response.getStatusCode(), is(200));
        assertThat(getWriter.response.getFirstHeader("access-control-allow-origin"), is("http://localhost:3000"));

        FakeResponseWriter putWriter = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/serviceChaos")
            .withMethod("PUT")
            .withHeader("Origin", "http://localhost:3000")
            .withBody("{\"host\":\"upstream.svc\",\"chaos\":{\"errorStatus\":503}}");
        assertThat(httpState.handle(putRequest, putWriter, false), is(true));
        assertThat(putWriter.response.getStatusCode(), is(200));
        assertThat(putWriter.response.getFirstHeader("access-control-allow-origin"), is("http://localhost:3000"));

        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
    }

    @Test
    public void shouldAddCorsHeadersToDriftResponsesWithoutEnableCorsForApi() {
        // the Drift dashboard tab GETs /mockserver/drift (and clears via /drift/clear)
        // cross-origin from the UI dev server, so these responses must carry CORS headers
        // even when enableCORSForAPI is off (the default here)
        FakeResponseWriter getWriter = new FakeResponseWriter();
        HttpRequest getRequest = request("/mockserver/drift")
            .withMethod("GET")
            .withHeader("Origin", "http://localhost:3000");
        assertThat(httpState.handle(getRequest, getWriter, false), is(true));
        assertThat(getWriter.response.getStatusCode(), is(200));
        assertThat(getWriter.response.getFirstHeader("access-control-allow-origin"), is("http://localhost:3000"));

        FakeResponseWriter clearWriter = new FakeResponseWriter();
        HttpRequest clearRequest = request("/mockserver/drift/clear")
            .withMethod("PUT")
            .withHeader("Origin", "http://localhost:3000");
        assertThat(httpState.handle(clearRequest, clearWriter, false), is(true));
        assertThat(clearWriter.response.getStatusCode(), is(200));
        assertThat(clearWriter.response.getFirstHeader("access-control-allow-origin"), is("http://localhost:3000"));
    }

    @Test
    public void shouldReturnSingleNodeClusterStatusForInMemoryBackend() throws Exception {
        // given — the default backend is the in-memory single-node backend
        FakeResponseWriter getWriter = new FakeResponseWriter();
        HttpRequest getRequest = request("/mockserver/cluster").withMethod("GET");

        // when
        assertThat(httpState.handle(getRequest, getWriter, false), is(true));

        // then — a sensible degenerate JSON response (single local member, clustered=false)
        assertThat(getWriter.response.getStatusCode(), is(200));
        com.fasterxml.jackson.databind.JsonNode body =
            org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                .readTree(getWriter.response.getBodyAsString());
        assertThat(body.get("clustered").asBoolean(), is(false));
        assertThat(body.get("memberCount").asInt(), is(1));
        assertThat(body.get("nodeId").asText(), is(not(emptyOrNullString())));
        assertThat(body.get("coordinator").asText(), is(body.get("nodeId").asText()));
        com.fasterxml.jackson.databind.JsonNode members = body.get("members");
        assertThat(members.isArray(), is(true));
        assertThat(members.size(), is(1));
        assertThat(members.get(0).get("id").asText(), is(body.get("nodeId").asText()));
        assertThat(members.get(0).get("coordinator").asBoolean(), is(true));
        assertThat(members.get(0).get("local").asBoolean(), is(true));
    }

    @Test
    public void shouldAddCorsHeadersToClusterResponse() {
        // the dashboard GETs /mockserver/cluster cross-origin from the UI dev server
        FakeResponseWriter getWriter = new FakeResponseWriter();
        HttpRequest getRequest = request("/mockserver/cluster")
            .withMethod("GET")
            .withHeader("Origin", "http://localhost:3000");
        assertThat(httpState.handle(getRequest, getWriter, false), is(true));
        assertThat(getWriter.response.getStatusCode(), is(200));
        assertThat(getWriter.response.getFirstHeader("access-control-allow-origin"), is("http://localhost:3000"));
    }

    @Test
    public void shouldRejectServiceChaosWithTtlBelowOne() throws Exception {
        FakeResponseWriter writer = new FakeResponseWriter();
        HttpRequest putRequest = request("/mockserver/serviceChaos")
            .withMethod("PUT")
            .withBody("{\"host\":\"upstream.svc\",\"chaos\":{\"errorStatus\":503},\"ttlMillis\":0}");
        assertThat(httpState.handle(putRequest, writer, false), is(true));
        assertThat(writer.response.getStatusCode(), is(400));
        assertThat(writer.response.getBodyAsString(), containsString("'ttlMillis' must be >= 1"));
    }

    @Test
    public void shouldRejectClockWithEmptyBody() throws Exception {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clockRequest = request("/mockserver/clock")
            .withMethod("PUT")
            .withBody("");

        // when
        boolean handle = httpState.handle(clockRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("request body is required"));
    }

    @Test
    public void shouldHandleOidcRequestWithDefaults() {
        // given
        HttpRequest oidcRequest = request("/mockserver/oidc")
            .withMethod("PUT");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(oidcRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        Expectation[] returnedExpectations = expectationSerializer.deserializeArray(
            responseWriter.response.getBodyAsString(), true
        );
        // discovery, jwks, token, authorize, userinfo, introspection, revocation, logout, device_authorization
        assertThat(returnedExpectations.length, is(9));

        // Verify the device-authorization endpoint (added with the OAuth2 device grant) is matchable
        assertThat(httpState.firstMatchingExpectation(
            request("/device_authorization").withMethod("POST")
        ), is(notNullValue()));

        // Verify the discovery endpoint is now matchable. It is served by a class callback rather
        // than a static response so the issuer can be derived per request from the Host header (OIDC
        // Discovery 4.3 requires the advertised issuer to match the URL the client fetched it from);
        // this previously asserted against a pre-baked response body.
        org.mockserver.mock.Expectation discoveryExpectation = httpState.firstMatchingExpectation(
            request("/.well-known/openid-configuration").withMethod("GET"));
        assertThat(discoveryExpectation, is(notNullValue()));
        assertThat(discoveryExpectation.getHttpResponseClassCallback().getCallbackClass(),
            is(org.mockserver.oidc.OidcDiscoveryCallback.class.getName()));

        HttpResponse discoveryResponse = new org.mockserver.oidc.OidcDiscoveryCallback().handle(
            request("/.well-known/openid-configuration").withMethod("GET").withHeader("host", "localhost:1080"));
        assertThat(discoveryResponse.getStatusCode(), is(200));
        assertThat(discoveryResponse.getBodyAsString(), containsString("\"issuer\""));
    }

    @Test
    public void shouldHandleOidcRequestWithCustomConfig() {
        // given
        HttpRequest oidcRequest = request("/mockserver/oidc")
            .withMethod("PUT")
            .withBody("{\"issuer\":\"https://custom.idp\",\"subject\":\"custom-sub\",\"tokenPath\":\"/custom/token\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(oidcRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));

        // Verify the token endpoint matches the custom path
        Expectation tokenMatch = httpState.firstMatchingExpectation(
            request("/custom/token").withMethod("POST")
        );
        assertThat(tokenMatch, is(notNullValue()));
        // /token is now served by the OidcTokenCallback class callback (authorization-code flow),
        // so it has no static httpResponse — assert the callback wiring instead of a response body.
        assertThat(tokenMatch.getHttpResponseClassCallback(), is(notNullValue()));
        assertThat(tokenMatch.getHttpResponseClassCallback().getCallbackClass(), containsString("OidcTokenCallback"));
    }

    @Test
    public void shouldHandleOidcRequestWithEmptyBody() {
        // given
        HttpRequest oidcRequest = request("/mockserver/oidc")
            .withMethod("PUT")
            .withBody("");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(oidcRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        Expectation[] returnedExpectations = expectationSerializer.deserializeArray(
            responseWriter.response.getBodyAsString(), true
        );
        // discovery, jwks, token, authorize, userinfo, introspection, revocation, logout, device_authorization
        assertThat(returnedExpectations.length, is(9));
    }

    @Test
    public void shouldHandleInvalidOidcRequest() {
        // given
        HttpRequest oidcRequest = request("/mockserver/oidc")
            .withMethod("PUT")
            .withBody("{invalid json");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(oidcRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    // --- PUT /saml tests ---

    @Test
    public void shouldHandleSamlRequestWithDefaults() {
        // given
        HttpRequest samlRequest = request("/mockserver/saml")
            .withMethod("PUT");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(samlRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        Expectation[] returnedExpectations = expectationSerializer.deserializeArray(
            responseWriter.response.getBodyAsString(), true
        );
        assertThat(returnedExpectations.length, is(3));

        // Verify the metadata endpoint is now matchable and returns SAML metadata
        HttpResponse metadataResponse = httpState.firstMatchingExpectation(
            request("/saml/metadata").withMethod("GET")
        ).getHttpResponse();
        assertThat(metadataResponse, is(notNullValue()));
        assertThat(metadataResponse.getStatusCode(), is(200));
        assertThat(metadataResponse.getBodyAsString(), containsString("IDPSSODescriptor"));
        assertThat(metadataResponse.getBodyAsString(), containsString("SingleSignOnService"));
    }

    @Test
    public void shouldHandleSamlRequestWithCustomConfig() {
        // given
        HttpRequest samlRequest = request("/mockserver/saml")
            .withMethod("PUT")
            .withBody("{\"idpEntityId\":\"https://custom.idp/entity\",\"ssoServiceUrl\":\"/custom/sso\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(samlRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));

        // Verify the SSO endpoint matches the custom path and is served by the class callback
        Expectation ssoMatch = httpState.firstMatchingExpectation(
            request("/custom/sso").withMethod("GET")
        );
        assertThat(ssoMatch, is(notNullValue()));
        assertThat(ssoMatch.getHttpResponseClassCallback(), is(notNullValue()));
        assertThat(ssoMatch.getHttpResponseClassCallback().getCallbackClass(), containsString("SamlSsoCallback"));
    }

    @Test
    public void shouldHandleSamlRequestWithEmptyBody() {
        // given
        HttpRequest samlRequest = request("/mockserver/saml")
            .withMethod("PUT")
            .withBody("");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(samlRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        Expectation[] returnedExpectations = expectationSerializer.deserializeArray(
            responseWriter.response.getBodyAsString(), true
        );
        assertThat(returnedExpectations.length, is(3));
    }

    @Test
    public void shouldHandleInvalidSamlRequest() {
        // given
        HttpRequest samlRequest = request("/mockserver/saml")
            .withMethod("PUT")
            .withBody("{invalid json");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(samlRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    // --- PUT /import tests ---

    @Test
    public void shouldHandleImportHarAutoDetected() {
        // given
        String har = "{\"log\":{\"entries\":[" +
            "{\"request\":{\"method\":\"GET\",\"url\":\"http://example.com/api/test\"}," +
            "\"response\":{\"status\":200,\"content\":{\"text\":\"{\\\"ok\\\":true}\"}}}" +
            "]}}";
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withBody(har);
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        Expectation[] returnedExpectations = expectationSerializer.deserializeArray(
            responseWriter.response.getBodyAsString(), true
        );
        assertThat(returnedExpectations.length, is(1));

        // Verify the expectation is now matchable
        Expectation match = httpState.firstMatchingExpectation(
            request("/api/test").withMethod("GET")
        );
        assertThat(match, is(notNullValue()));
        assertThat(match.getHttpResponse().getStatusCode(), is(200));
    }

    @Test
    public void shouldHandleImportPostmanAutoDetected() {
        // given
        String postman = "{\"info\":{\"name\":\"Test\"},\"item\":[" +
            "{\"name\":\"Get Health\",\"request\":{\"method\":\"GET\",\"url\":\"http://example.com/health\"}," +
            "\"response\":[{\"code\":200,\"body\":\"{\\\"status\\\":\\\"up\\\"}\"}]}" +
            "]}";
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withBody(postman);
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        Expectation[] returnedExpectations = expectationSerializer.deserializeArray(
            responseWriter.response.getBodyAsString(), true
        );
        assertThat(returnedExpectations.length, is(1));
    }

    @Test
    public void shouldHandleImportWithFormatQueryParam() {
        // given — use ?format=har
        String har = "{\"log\":{\"entries\":[" +
            "{\"request\":{\"method\":\"GET\",\"url\":\"http://example.com/data\"}," +
            "\"response\":{\"status\":200}}" +
            "]}}";
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "har")
            .withBody(har);
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
    }

    @Test
    public void shouldHandleImportMalformedJson() {
        // given
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withBody("{not valid json");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    @Test
    public void shouldHandleImportEmptyBody() {
        // given
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withBody("");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    @Test
    public void shouldHandleImportUnsupportedFormat() {
        // given
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "insomnia")
            .withBody("{\"some\":\"json\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("unsupported import format"));
    }

    @Test
    public void shouldHandleImportUnrecognisedAutoDetect() {
        // given — JSON that is neither HAR nor Postman
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withBody("{\"something\":\"else\"}");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("unable to auto-detect"));
    }

    private static final String PACT_V3_CONTRACT = "{" +
        "\"consumer\":{\"name\":\"c\"},\"provider\":{\"name\":\"p\"}," +
        "\"interactions\":[{" +
        "  \"description\":\"get health\"," +
        "  \"request\":{\"method\":\"GET\",\"path\":\"/health\"}," +
        "  \"response\":{\"status\":200,\"body\":{\"status\":\"up\"}}" +
        "}]," +
        "\"metadata\":{\"pactSpecification\":{\"version\":\"3.0.0\"}}}";

    @Test
    public void shouldHandleImportPactWithFormatQueryParam() {
        // given
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "pact")
            .withBody(PACT_V3_CONTRACT);
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        Expectation[] returnedExpectations = expectationSerializer.deserializeArray(
            responseWriter.response.getBodyAsString(), true
        );
        assertThat(returnedExpectations.length, is(1));

        // the imported expectation is now matchable
        Expectation match = httpState.firstMatchingExpectation(
            request("/health").withMethod("GET")
        );
        assertThat(match, is(notNullValue()));
        assertThat(match.getHttpResponse().getStatusCode(), is(200));
    }

    @Test
    public void shouldHandleImportPactAutoDetected() {
        // given — no format param; top-level "interactions" array triggers Pact auto-detect
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withBody(PACT_V3_CONTRACT);
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        Expectation[] returnedExpectations = expectationSerializer.deserializeArray(
            responseWriter.response.getBodyAsString(), true
        );
        assertThat(returnedExpectations.length, is(1));
    }

    @Test
    public void shouldHandlePactImportDedicatedRoute() {
        // given
        HttpRequest importRequest = request("/mockserver/pact/import")
            .withMethod("PUT")
            .withBody(PACT_V3_CONTRACT);
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        Expectation[] returnedExpectations = expectationSerializer.deserializeArray(
            responseWriter.response.getBodyAsString(), true
        );
        assertThat(returnedExpectations.length, is(1));

        // the imported expectation is now matchable
        Expectation match = httpState.firstMatchingExpectation(
            request("/health").withMethod("GET")
        );
        assertThat(match, is(notNullValue()));
    }

    @Test
    public void shouldHandlePactImportEmptyBody() {
        // given
        HttpRequest importRequest = request("/mockserver/pact/import")
            .withMethod("PUT")
            .withBody("");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    // ---- Pact Verify Endpoint Tests ----

    @Test
    public void shouldHandlePactVerifyReturning202WhenAllInteractionsPass() {
        // given — register an expectation
        httpState.add(new Expectation(
            request().withMethod("GET").withPath("/health")
        ).thenRespond(
            response().withStatusCode(200)
        ));

        String pactContract = "{\n"
            + "  \"consumer\": {\"name\": \"test\"},\n"
            + "  \"provider\": {\"name\": \"provider\"},\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"health check\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/health\"},\n"
            + "    \"response\": {\"status\": 200}\n"
            + "  }],\n"
            + "  \"metadata\": {\"pactSpecification\": {\"version\": \"3.0.0\"}}\n"
            + "}";

        HttpRequest verifyRequest = request("/mockserver/pact/verify")
            .withMethod("PUT")
            .withBody(pactContract);
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(verifyRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(202));
        assertThat(responseWriter.response.getBodyAsString(), containsString("\"verified\" : true"));
    }

    @Test
    public void shouldHandlePactVerifyReturning406WhenInteractionFails() {
        // given — register an expectation with status 200
        httpState.add(new Expectation(
            request().withMethod("GET").withPath("/health")
        ).thenRespond(
            response().withStatusCode(200)
        ));

        // Pact expects status 204 — mismatch
        String pactContract = "{\n"
            + "  \"consumer\": {\"name\": \"test\"},\n"
            + "  \"provider\": {\"name\": \"provider\"},\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"health check\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/health\"},\n"
            + "    \"response\": {\"status\": 204}\n"
            + "  }],\n"
            + "  \"metadata\": {\"pactSpecification\": {\"version\": \"3.0.0\"}}\n"
            + "}";

        HttpRequest verifyRequest = request("/mockserver/pact/verify")
            .withMethod("PUT")
            .withBody(pactContract);
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(verifyRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(406));
        assertThat(responseWriter.response.getBodyAsString(), containsString("\"verified\" : false"));
        assertThat(responseWriter.response.getBodyAsString(), containsString("status code mismatch"));
    }

    @Test
    public void shouldHandlePactVerifyReturning400OnEmptyBody() {
        HttpRequest verifyRequest = request("/mockserver/pact/verify")
            .withMethod("PUT")
            .withBody("");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(verifyRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("must not be empty"));
    }

    @Test
    public void shouldHandlePactVerifyReturning400OnMalformedJson() {
        HttpRequest verifyRequest = request("/mockserver/pact/verify")
            .withMethod("PUT")
            .withBody("not json at all {{{");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(verifyRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    // ---- Replay endpoint tests ----

    @Test
    public void shouldHandleReplayRequestSuccessfully() {
        // given — wire a fake replay handler that echoes back a fixed response
        HttpResponse upstreamResponse = response()
            .withStatusCode(200)
            .withBody("upstream OK");
        httpState.setReplayHandler(req -> CompletableFuture.completedFuture(upstreamResponse));

        HttpRequest replayRequest = request("/mockserver/replay")
            .withMethod("PUT")
            .withBody(
                "{\"method\":\"GET\",\"path\":\"/api/test\",\"headers\":[{\"name\":\"host\",\"values\":[\"example.com\"]}]}"
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(replayRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("upstream OK"));
    }

    @Test
    public void shouldReturnBadRequestForEmptyReplayBody() {
        // given — wire a replay handler (it won't be called)
        httpState.setReplayHandler(req -> CompletableFuture.completedFuture(response()));

        HttpRequest replayRequest = request("/mockserver/replay")
            .withMethod("PUT")
            .withBody("");
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(replayRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("request body must contain an HttpRequest JSON definition"));
    }

    @Test
    public void shouldReturn501WhenNoReplayHandlerIsWired() {
        // given — do NOT set a replay handler (it stays null)
        HttpRequest replayRequest = request("/mockserver/replay")
            .withMethod("PUT")
            .withBody(
                "{\"method\":\"GET\",\"path\":\"/api/test\",\"headers\":[{\"name\":\"host\",\"values\":[\"example.com\"]}]}"
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(replayRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(501));
        assertThat(responseWriter.response.getBodyAsString(), containsString("replay is not available"));
    }

    @Test
    public void shouldReturnBadGatewayWhenReplayFails() {
        // given — wire a replay handler that fails
        httpState.setReplayHandler(req -> {
            CompletableFuture<HttpResponse> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("Connection refused"));
            return future;
        });

        HttpRequest replayRequest = request("/mockserver/replay")
            .withMethod("PUT")
            .withBody(
                "{\"method\":\"GET\",\"path\":\"/api/test\",\"headers\":[{\"name\":\"host\",\"values\":[\"example.com\"]}]}"
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(replayRequest, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(502));
        assertThat(responseWriter.response.getBodyAsString(), containsString("Connection refused"));
    }

    @Test
    public void shouldReturn403WhenReplayTargetBlockedBySSRFPolicy() {
        // given — enable SSRF protection and wire a replay handler that should NOT be reached
        configuration.forwardProxyBlockPrivateNetworks(true);
        final boolean[] handlerCalled = {false};
        httpState.setReplayHandler(req -> {
            handlerCalled[0] = true;
            return CompletableFuture.completedFuture(response().withStatusCode(200));
        });

        // replay to a loopback address (127.0.0.1 — blocked by SSRF policy)
        HttpRequest replayRequest = request("/mockserver/replay")
            .withMethod("PUT")
            .withBody(
                "{\"method\":\"GET\",\"path\":\"/internal\",\"headers\":[{\"name\":\"host\",\"values\":[\"127.0.0.1:8080\"]}]}"
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(replayRequest, responseWriter, false);

        // then — 403 returned and handler was never called
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(403));
        assertThat(responseWriter.response.getBodyAsString(), containsString("SSRF policy"));
        assertThat(responseWriter.response.getBodyAsString(), containsString("loopback"));
        assertThat("replay handler must not be called when SSRF policy blocks", handlerCalled[0], is(false));
    }

    @Test
    public void shouldReturn403WhenReplaySocketAddressBlockedBySSRFPolicy() {
        // given — enable SSRF protection and wire a replay handler that should NOT be reached
        configuration.forwardProxyBlockPrivateNetworks(true);
        final boolean[] handlerCalled = {false};
        httpState.setReplayHandler(req -> {
            handlerCalled[0] = true;
            return CompletableFuture.completedFuture(response().withStatusCode(200));
        });

        // replay using socketAddress to a private network address (192.168.1.1 — blocked)
        HttpRequest replayRequest = request("/mockserver/replay")
            .withMethod("PUT")
            .withBody(
                "{\"method\":\"GET\",\"path\":\"/internal\",\"socketAddress\":{\"host\":\"192.168.1.1\",\"port\":9090,\"scheme\":\"HTTP\"}}"
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(replayRequest, responseWriter, false);

        // then — 403 returned and handler was never called
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(403));
        assertThat(responseWriter.response.getBodyAsString(), containsString("SSRF policy"));
        assertThat(responseWriter.response.getBodyAsString(), containsString("private network"));
        assertThat("replay handler must not be called when SSRF policy blocks", handlerCalled[0], is(false));
    }

    @Test
    public void shouldAllowReplayToLoopbackWhenSSRFPolicyDisabled() {
        // given — SSRF protection disabled (the default)
        configuration.forwardProxyBlockPrivateNetworks(false);
        HttpResponse upstreamResponse = response()
            .withStatusCode(200)
            .withBody("local OK");
        httpState.setReplayHandler(req -> CompletableFuture.completedFuture(upstreamResponse));

        HttpRequest replayRequest = request("/mockserver/replay")
            .withMethod("PUT")
            .withBody(
                "{\"method\":\"GET\",\"path\":\"/local\",\"headers\":[{\"name\":\"host\",\"values\":[\"127.0.0.1:8080\"]}]}"
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(replayRequest, responseWriter, false);

        // then — replay proceeds normally when SSRF policy is off
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("local OK"));
    }

    @Test
    public void shouldReturn502WhenUpstreamResponseBodyExceedsMaxSize() {
        // given — wire a replay handler that returns an oversized response
        byte[] oversizedBody = new byte[10 * 1024 * 1024 + 1]; // just over 10 MB
        Arrays.fill(oversizedBody, (byte) 'x');
        httpState.setReplayHandler(req -> CompletableFuture.completedFuture(
            response().withStatusCode(200).withBody(new String(oversizedBody, UTF_8))
        ));

        HttpRequest replayRequest = request("/mockserver/replay")
            .withMethod("PUT")
            .withBody(
                "{\"method\":\"GET\",\"path\":\"/big\",\"headers\":[{\"name\":\"host\",\"values\":[\"example.com\"]}]}"
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(replayRequest, responseWriter, false);

        // then — 502 returned for oversized upstream response
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(502));
        assertThat(responseWriter.response.getBodyAsString(), containsString("response too large to return via control plane"));
    }

    @Test
    public void shouldProperlyJsonEscapeReplayErrorMessages() {
        // given — wire a replay handler that fails with a message containing JSON-hostile characters
        httpState.setReplayHandler(req -> {
            CompletableFuture<HttpResponse> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("line1\nline2\\path \"quoted\""));
            return future;
        });

        HttpRequest replayRequest = request("/mockserver/replay")
            .withMethod("PUT")
            .withBody(
                "{\"method\":\"GET\",\"path\":\"/test\",\"headers\":[{\"name\":\"host\",\"values\":[\"example.com\"]}]}"
            );
        FakeResponseWriter responseWriter = new FakeResponseWriter();

        // when
        boolean handle = httpState.handle(replayRequest, responseWriter, false);

        // then — the response body must be valid JSON (no unescaped quotes/newlines)
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(502));
        String responseBody = responseWriter.response.getBodyAsString();
        // Verify it parses as valid JSON
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
        } catch (Exception e) {
            fail("Response body is not valid JSON: " + responseBody);
        }
        // Verify the error message content is preserved (escaped)
        assertThat(responseBody, containsString("line1"));
        assertThat(responseBody, containsString("line2"));
        assertThat(responseBody, containsString("quoted"));
    }

    // a HAR with one entry whose JSON request body contains a default-sensitive field
    // (password) plus a non-default field (foo); used to exercise import redaction over
    // the REST endpoint (the importer filters volatile headers independently of
    // redaction, so the stable signal is the JSON body fields)
    private static final String IMPORT_REDACTION_HAR =
        "{" +
            "\"log\":{\"entries\":[{" +
            "\"request\":{" +
            "\"method\":\"POST\"," +
            "\"url\":\"http://example.com/login\"," +
            "\"postData\":{\"mimeType\":\"application/json\",\"text\":\"{\\\"password\\\":\\\"hunter2\\\",\\\"foo\\\":\\\"bar\\\"}\"}" +
            "}," +
            "\"response\":{\"status\":200}" +
            "}]}}";

    @Test
    public void shouldImportWithRedactionEnabledByDefault() {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "har")
            .withBody(IMPORT_REDACTION_HAR);

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then — the default-sensitive body field is redacted
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, not(containsString("hunter2")));
        assertThat(body, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
        // foo is not a default-sensitive field, so it is kept verbatim
        assertThat(body, containsString("bar"));
    }

    @Test
    public void shouldImportVerbatimWhenRedactSensitiveDataIsFalse() {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "har")
            .withQueryStringParameter("redactSensitiveData", "false")
            .withBody(IMPORT_REDACTION_HAR);

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then — nothing is redacted; the real secret is kept verbatim
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("hunter2"));
        assertThat(body, containsString("bar"));
        assertThat(body, not(containsString(FixtureRedactor.REDACTED_PLACEHOLDER)));
    }

    @Test
    public void shouldRedactAdditionalBodyFieldsWhenRequested() {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest importRequest = request("/mockserver/import")
            .withMethod("PUT")
            .withQueryStringParameter("format", "har")
            .withQueryStringParameter("additionalRedactedBodyFields", "foo")
            .withBody(IMPORT_REDACTION_HAR);

        // when
        boolean handle = httpState.handle(importRequest, responseWriter, false);

        // then — the extra "foo" field is redacted in addition to the defaults
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, not(containsString("\"bar\"")));
        assertThat(body, not(containsString("hunter2")));
        assertThat(body, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
    }

    @Test
    public void shouldImportGraphQLSchemaAsExpectations() {
        // given - a GraphQL SDL document
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest graphqlImport = request("/mockserver/graphql")
            .withMethod("PUT")
            .withBody("type Query { hello: String } type Mutation { ping: String }");

        // when
        boolean handle = httpState.handle(graphqlImport, responseWriter, false);

        // then - one expectation per root operation type is created and persisted
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        Expectation[] created = expectationSerializer.deserializeArray(responseWriter.response.getBodyAsString(), false);
        assertThat(created.length, is(2));

        // and - a real GraphQL query now matches the imported query expectation
        Expectation matchedQuery = httpState.firstMatchingExpectation(
            request().withMethod("POST").withPath("/graphql").withBody("{\"query\":\"{ hello }\"}"));
        assertThat(matchedQuery, is(notNullValue()));

        // and - a mutation matches the (distinct) mutation expectation, not the query one
        Expectation matchedMutation = httpState.firstMatchingExpectation(
            request().withMethod("POST").withPath("/graphql").withBody("{\"query\":\"mutation { ping }\"}"));
        assertThat(matchedMutation, is(notNullValue()));
        assertThat(((GraphQLBody) ((HttpRequest) matchedMutation.getHttpRequest()).getBody()).getQuery(),
            startsWith("mutation"));
    }

    @Test
    public void shouldReturnBadRequestForMalformedGraphQLSchema() {
        // given
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest graphqlImport = request("/mockserver/graphql")
            .withMethod("PUT")
            .withBody("type Query { this is not valid");

        // when
        boolean handle = httpState.handle(graphqlImport, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    @Test
    public void shouldRouteAsyncApiHttpImportAndReportModuleNotAvailable() {
        // given - mockserver-async is not on the core test classpath, so the route must
        // still be recognised and respond with 501 rather than falling through to the data plane
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest asyncApiImport = request("/mockserver/asyncapi/http")
            .withMethod("PUT")
            .withBody("{\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"T\",\"version\":\"1.0.0\"},\"channels\":{}}");

        // when
        boolean handle = httpState.handle(asyncApiImport, responseWriter, false);

        // then
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(501));
        assertThat(responseWriter.response.getBodyAsString(), containsString("mockserver-async"));
    }

}

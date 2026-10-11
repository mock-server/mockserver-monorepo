package org.mockserver.mock;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.SocketAddress;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.CREATED_EXPECTATION;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;

/**
 * Every control-plane request that loads the OpenAPI spec its body names must not hold the calling (event-loop)
 * thread while the spec URL is fetched: handle() returns while the fetch is still held, and the response follows.
 */
public class HttpStateOpenAPISpecLoadThreadTest {

    private static final String SPEC = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Thread\",\"version\":\"1\"}," +
        "\"paths\":{\"/spec-load-pets\":{\"get\":{\"operationId\":\"listSpecLoadPets\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";

    private final CountDownLatch specRequested = new CountDownLatch(1);
    private final CountDownLatch releaseSpec = new CountDownLatch(1);
    private HttpServer specServer;
    private ExecutorService specServerExecutor;
    private Scheduler scheduler;
    private ExecutorService caller;
    private HttpState httpState;

    private static class RecordingResponseWriter extends ResponseWriter {
        final CompletableFuture<HttpResponse> response = new CompletableFuture<>();
        volatile Thread writer;

        RecordingResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            writer = Thread.currentThread();
            this.response.complete(response);
        }
    }

    @Before
    public void setUp() throws Exception {
        specServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("localhost"), 0), 0);
        specServerExecutor = Executors.newCachedThreadPool();
        specServer.setExecutor(specServerExecutor);
        specServer.createContext("/", exchange -> {
            specRequested.countDown();
            try {
                releaseSpec.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = SPEC.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        specServer.start();
        Configuration configuration = configuration().logLevel("INFO");
        MockServerLogger mockServerLogger = new MockServerLogger(configuration, HttpStateOpenAPISpecLoadThreadTest.class);
        scheduler = new Scheduler(configuration, mockServerLogger);
        httpState = new HttpState(configuration, mockServerLogger, scheduler);
        // a verification only loads its spec to match a recorded request
        httpState.log(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(request("/spec-load-pets").withMethod("GET")));
        caller = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() {
        releaseSpec.countDown();
        specServer.stop(0);
        specServerExecutor.shutdownNow();
        caller.shutdownNow();
        httpState.stop();
        scheduler.shutdown();
    }

    private String specUrl() {
        return "http://localhost:" + specServer.getAddress().getPort() + "/" + UUID.randomUUID() + "/openapi.json";
    }

    private void assertHandleReturnsWhileTheSpecIsFetched(HttpRequest request, int expectedStatusCode) throws Exception {
        RecordingResponseWriter responseWriter = new RecordingResponseWriter();

        CompletableFuture<Boolean> handled = CompletableFuture.supplyAsync(() -> httpState.handle(request, responseWriter, false), caller);

        if (!specRequested.await(20, TimeUnit.SECONDS)) {
            fail("the spec URL was never fetched, response: " + responseWriter.response.getNow(null));
        }
        assertThat(handled.get(10, TimeUnit.SECONDS), is(true));
        assertThat(responseWriter.response.isDone(), is(false));

        releaseSpec.countDown();
        HttpResponse response = responseWriter.response.get(20, TimeUnit.SECONDS);
        assertThat(response.getBodyAsString(), response.getStatusCode(), is(expectedStatusCode));
    }

    @Test
    public void expectationWithOpenAPIMatcher() throws Exception {
        assertHandleReturnsWhileTheSpecIsFetched(request("/mockserver/expectation").withMethod("PUT")
            .withBody("{\"httpRequest\":{\"specUrlOrPayload\":\"" + specUrl() + "\"},\"httpResponse\":{\"statusCode\":200}}"), 201);
    }

    @Test
    public void verifyWithOpenAPIMatcher() throws Exception {
        assertHandleReturnsWhileTheSpecIsFetched(request("/mockserver/verify").withMethod("PUT")
            .withBody("{\"httpRequest\":{\"specUrlOrPayload\":\"" + specUrl() + "\"},\"times\":{\"atLeast\":1}}"), 202);
    }

    @Test
    public void verifySequenceWithOpenAPIMatcher() throws Exception {
        assertHandleReturnsWhileTheSpecIsFetched(request("/mockserver/verifySequence").withMethod("PUT")
            .withBody("{\"httpRequests\":[{\"specUrlOrPayload\":\"" + specUrl() + "\"}]}"), 202);
    }

    @Test
    public void retrieveWithOpenAPIMatcher() throws Exception {
        assertHandleReturnsWhileTheSpecIsFetched(request("/mockserver/retrieve").withMethod("PUT").withQueryStringParameter("type", "REQUESTS")
            .withBody("{\"specUrlOrPayload\":\"" + specUrl() + "\"}"), 200);
    }

    @Test
    public void clearWithOpenAPIMatcher() throws Exception {
        assertHandleReturnsWhileTheSpecIsFetched(request("/mockserver/clear").withMethod("PUT").withQueryStringParameter("type", "LOG")
            .withBody("{\"specUrlOrPayload\":\"" + specUrl() + "\"}"), 200);
    }

    @Test
    public void loadScenarioGeneratedFromOpenAPI() throws Exception {
        assertHandleReturnsWhileTheSpecIsFetched(request("/mockserver/loadScenario/generateFromOpenAPI").withMethod("PUT")
            .withBody("{\"name\":\"spec-load-" + UUID.randomUUID() + "\",\"specUrlOrPayload\":\"" + specUrl() + "\",\"target\":{\"host\":\"localhost\",\"port\":1080}}"), 200);
    }

    @Test
    public void contractTest() throws Exception {
        httpState.setReplayHandler(req -> CompletableFuture.completedFuture(org.mockserver.model.HttpResponse.response().withStatusCode(200)));

        assertHandleReturnsWhileTheSpecIsFetched(request("/mockserver/contractTest").withMethod("PUT")
            .withBody("{\"spec\":\"" + specUrl() + "\",\"baseUrl\":\"http://localhost:1080\"}"), 200);
    }

    @Test
    public void trafficValidation() throws Exception {
        assertHandleReturnsWhileTheSpecIsFetched(request("/mockserver/trafficValidate").withMethod("PUT")
            .withBody("{\"spec\":\"" + specUrl() + "\"}"), 200);
    }

    @Test
    public void whatAnOffloadedRequestLogsCarriesThePortItWasReceivedOn() throws Exception {
        releaseSpec.countDown();
        RecordingResponseWriter responseWriter = new RecordingResponseWriter();
        HttpRequest request = request("/mockserver/expectation").withMethod("PUT")
            .withSocketAddress("localhost", 1234, SocketAddress.Scheme.HTTP)
            .withBody("{\"httpRequest\":{\"specUrlOrPayload\":\"" + specUrl() + "\"},\"httpResponse\":{\"statusCode\":200}}");

        assertThat(CompletableFuture.supplyAsync(() -> httpState.handle(request, responseWriter, false), caller).get(10, TimeUnit.SECONDS), is(true));
        assertThat(responseWriter.response.get(20, TimeUnit.SECONDS).getStatusCode(), is(201));

        CompletableFuture<List<LogEntry>> logEntries = new CompletableFuture<>();
        httpState.getMockServerLog().retrieveMessageLogEntries(null, logEntries::complete);
        List<Integer> createdExpectationPorts = logEntries.get(20, TimeUnit.SECONDS).stream()
            .filter(logEntry -> logEntry.getType() == CREATED_EXPECTATION)
            .map(LogEntry::getPort)
            .collect(Collectors.toList());
        assertThat(createdExpectationPorts, contains(1234));
    }

    @Test
    public void breakpointWithOpenAPIMatcher() throws Exception {
        String clientId = "spec-load-" + UUID.randomUUID();
        try {
            assertHandleReturnsWhileTheSpecIsFetched(request("/mockserver/breakpoint/matcher").withMethod("PUT")
                .withBody("{\"httpRequest\":{\"specUrlOrPayload\":\"" + specUrl() + "\"},\"phases\":[\"REQUEST\"],\"clientId\":\"" + clientId + "\"}"), 201);
        } finally {
            org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().removeByClientId(clientId);
        }
    }

    @Test
    public void recordingsPromotedWithOpenAPIFilter() throws Exception {
        assertHandleReturnsWhileTheSpecIsFetched(request("/mockserver/recordings/promote").withMethod("PUT")
            .withBody("{\"specUrlOrPayload\":\"" + specUrl() + "\"}"), 201);
    }

    @Test
    public void servletDeploymentAnswersAContractTestBeforeHandleReturns() {
        releaseSpec.countDown();
        httpState.setReplayHandler(req -> CompletableFuture.completedFuture(org.mockserver.model.HttpResponse.response().withStatusCode(200)));
        RecordingResponseWriter responseWriter = new RecordingResponseWriter();

        assertThat(httpState.handle(request("/mockserver/contractTest").withMethod("PUT")
            .withBody("{\"spec\":\"" + specUrl() + "\",\"baseUrl\":\"http://localhost:1080\"}"), responseWriter, true), is(true));

        assertThat(responseWriter.response.isDone(), is(true));
        assertThat(responseWriter.response.join().getStatusCode(), is(200));
    }

    @Test
    public void servletDeploymentAnswersATrafficValidationBeforeHandleReturns() {
        releaseSpec.countDown();
        RecordingResponseWriter responseWriter = new RecordingResponseWriter();

        assertThat(httpState.handle(request("/mockserver/trafficValidate").withMethod("PUT")
            .withBody("{\"spec\":\"" + specUrl() + "\"}"), responseWriter, true), is(true));

        assertThat(responseWriter.response.isDone(), is(true));
        assertThat(responseWriter.response.join().getStatusCode(), is(200));
    }

    @Test
    public void expectationWithoutAnOpenAPIDefinitionIsStillAnsweredBeforeHandleReturns() {
        RecordingResponseWriter responseWriter = new RecordingResponseWriter();

        assertThat(httpState.handle(request("/mockserver/expectation").withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/inline\"},\"httpResponse\":{\"statusCode\":200}}"), responseWriter, false), is(true));

        assertThat(responseWriter.response.isDone(), is(true));
        assertThat(responseWriter.writer, sameInstance(Thread.currentThread()));
        assertThat(responseWriter.response.join().getStatusCode(), is(201));
    }

    @Test
    public void servletDeploymentAnswersAnOpenAPIMatcherBeforeHandleReturns() {
        releaseSpec.countDown();
        RecordingResponseWriter responseWriter = new RecordingResponseWriter();

        assertThat(httpState.handle(request("/mockserver/expectation").withMethod("PUT")
            .withBody("{\"httpRequest\":{\"specUrlOrPayload\":\"" + specUrl() + "\"},\"httpResponse\":{\"statusCode\":200}}"), responseWriter, true), is(true));

        assertThat(responseWriter.response.isDone(), is(true));
        assertThat(responseWriter.writer, sameInstance(Thread.currentThread()));
        assertThat(responseWriter.response.join().getStatusCode(), is(201));
    }
}

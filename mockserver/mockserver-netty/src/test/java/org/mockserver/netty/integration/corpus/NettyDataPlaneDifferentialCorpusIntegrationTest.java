package org.mockserver.netty.integration.corpus;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.EventLogTestAccess;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;
import org.mockserver.model.RequestDefinition;
import org.mockserver.netty.MockServer;
import org.mockserver.serialization.LogEntrySerializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.ConnectionOptions.connectionOptions;
import static org.mockserver.model.Delay.delay;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.HttpTemplate.TemplateType.VELOCITY;
import static org.mockserver.model.HttpTemplate.template;
import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;
import static org.mockserver.model.SseEvent.sseEvent;
import static org.mockserver.model.StreamingPhysics.streamingPhysics;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.netty.integration.PipelinedHttp1Client.get;
import static org.mockserver.netty.integration.PipelinedHttp1Client.rawRequest;
import static org.mockserver.netty.integration.PipelinedHttp1Client.rawRequestWithBody;
import static org.mockserver.netty.integration.corpus.RecordingHttp2Client.h2Request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * The data-plane differential corpus for HTTP/1.1 and HTTP/2: for each scenario, the bytes the client receives
 * (status, header order, body, trailers, and chunk or DATA frame boundaries) and the serialised event-log entries,
 * compared byte for byte with a golden file under {@code src/test/resources/org/mockserver/netty/integration/corpus/dataplane}.
 * A change to the data plane that alters any of them turns this red; {@link CorpusNormaliser} lists the only values
 * rewritten first.
 * <p>
 * It runs in CI with the rest of the netty integration tests (the {@code rest} shard, which fails closed if it did
 * not run). To run it on its own, use {@code -am} so the server under test is built from this tree rather than
 * taken from {@code ~/.m2}: {@code ./mvnw -pl mockserver-netty -am verify -DskipTests
 * -Dit.test=NettyDataPlaneDifferentialCorpusIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false
 * -Dmockserver.failIfNoIntegrationTests=false -Djacoco.skip=true}.
 * <p>
 * The main server differs from the defaults in four ways, so the corpus covers these branches and not the defaults:
 * {@code attemptToProxyIfNoMatchingExpectation=false} (an unmatched request gets the no-proxy 404 or a static file),
 * {@code proxySetup=false}, {@code enableCORSForAPI=true} (so a data-plane preflight is answered), and
 * {@code driftDetectionEnabled=false}. Drift analysis runs after a forwarded response is written, on a scheduler
 * thread, and matches the request again; its extra {@code EXPECTATION_MATCHED} entry lands in the log at a racy
 * position, so it is not covered here.
 * <p>
 * To regenerate the golden files after an intended change, add {@code -Dmockserver.corpus.regenerate=true}, then
 * review the diff of the golden files. A failing run writes what it received to
 * {@code target/corpus-actual/<scenario>.txt}.
 */
public class NettyDataPlaneDifferentialCorpusIntegrationTest {

    private static final String GOLDEN_DIRECTORY = "src/test/resources/org/mockserver/netty/integration/corpus/dataplane";
    private static final long LOG_SETTLE_POLL_MILLIS = 100;
    private static final int LOG_SETTLE_STABLE_POLLS = 3;
    // how long a stream that may never end is watched before the transcript records it as open
    private static final long QUIET_MILLIS = 1_000;

    @ClassRule
    public static final TemporaryFolder FILES = new TemporaryFolder();

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServerClient breakpointClient;
    private static MockServer upstreamServer;
    private static MockServerClient upstreamClient;
    private static MockServer authServer;
    private static EventLoopGroup http2Group;

    @BeforeClass
    public static void startServers() throws Exception {
        Path staticFiles = FILES.newFolder("static").toPath();
        Files.write(staticFiles.resolve("static.txt"), "static file content – ✓\n".getBytes(StandardCharsets.UTF_8));

        upstreamServer = new MockServer(configuration().logLevel("WARN"), 0);
        upstreamClient = new MockServerClient("127.0.0.1", upstreamServer.getLocalPort());

        mockServer = new MockServer(configuration()
            .logLevel("INFO")
            .proxySetup(false)
            .attemptToProxyIfNoMatchingExpectation(false)
            .enableCORSForAPI(true)
            // see the class Javadoc: drift analysis logs at a racy position
            .driftDetectionEnabled(false)
            .staticFilesDirectory(staticFiles.toString()), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        breakpointClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());

        authServer = new MockServer(configuration()
            .logLevel("INFO")
            .proxySetup(false)
            .attemptToProxyIfNoMatchingExpectation(false)
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("corpus-token"), 0);

        http2Group = new NioEventLoopGroup(1);
    }

    @AfterClass
    public static void stopServers() {
        if (http2Group != null) {
            http2Group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
        stopQuietly(breakpointClient);
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        stopQuietly(upstreamClient);
        stopQuietly(upstreamServer);
        stopQuietly(authServer);
    }

    @Before
    public void resetServers() {
        mockServerClient.reset();
        upstreamClient.reset();
    }

    // single request

    @Test
    public void singleRequestOverHttp1() throws Exception {
        expectSingle();
        assertCorpus("single-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(get("/single"));
            client.readResponse();
        }));
    }

    @Test
    public void singleRequestOverHttp2() throws Exception {
        expectSingle();
        assertCorpus("single-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("GET", "/single"), null, true).awaitEnd()));
    }

    private static void expectSingle() {
        mockServerClient.when(request().withPath("/single")).withId("single").respond(response()
            .withStatusCode(200)
            .withHeader("x-zulu", "written first")
            .withHeader("x-alpha", "written second")
            .withHeader("Content-Type", "text/plain; charset=utf-8")
            .withCookie("session", "corpus")
            .withBody("single response body – café ✓"));
    }

    @Test
    public void trailersOverHttp1() throws Exception {
        expectTrailers();
        assertCorpus("trailers-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(get("/trailers"));
            client.readResponse();
        }));
    }

    @Test
    public void trailersOverHttp2() throws Exception {
        expectTrailers();
        assertCorpus("trailers-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("GET", "/trailers"), null, true).awaitEnd()));
    }

    private static void expectTrailers() {
        mockServerClient.when(request().withPath("/trailers")).withId("trailers").respond(response()
            .withBody("body with trailers")
            .withTrailers(header("x-checksum", "abc123"), header("x-trailer-two", "2")));
    }

    // pipelined HTTP/1.1: the log entries of each request are grouped, as the requests' entries may interleave

    @Test
    public void pipelinedRequestsOverHttp1() throws Exception {
        mockServerClient.when(request().withPath("/pipelined/one")).withId("pipelined-one").respond(response("one").withDelay(TimeUnit.MILLISECONDS, 100));
        mockServerClient.when(request().withPath("/pipelined/two")).withId("pipelined-two").respond(response("two"));
        mockServerClient.when(request().withPath("/pipelined/three")).withId("pipelined-three").respond(response("three"));
        assertCorpus("pipelined-http1", mockServer, true, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(get("/pipelined/one"), get("/pipelined/two"), get("/pipelined/three"));
            client.readResponse();
            client.readResponse();
            client.readResponse();
        }));
    }

    // forward

    @Test
    public void forwardOverHttp1() throws Exception {
        expectForward();
        assertCorpus("forward-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(get("/forward"));
            client.readResponse();
        }));
    }

    @Test
    public void forwardOverHttp2() throws Exception {
        expectForward();
        assertCorpus("forward-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("GET", "/forward"), null, true).awaitEnd()));
    }

    private static void expectForward() {
        upstreamClient.when(request().withPath("/forward")).respond(response("from upstream").withHeader("x-upstream", "yes"));
        mockServerClient.when(request().withPath("/forward")).withId("forward").forward(forward().withHost("127.0.0.1").withPort(upstreamServer.getLocalPort()));
    }

    @Test
    public void streamedForwardOverHttp1() throws Exception {
        expectStreamedForward();
        assertCorpus("streamed-forward-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(rawRequest("GET", "/streamedForward", "Accept: text/event-stream"));
            client.readResponse();
        }));
    }

    @Test
    public void streamedForwardOverHttp2() throws Exception {
        expectStreamedForward();
        assertCorpus("streamed-forward-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("GET", "/streamedForward", "accept", "text/event-stream"), null, true).awaitEnd()));
    }

    private static void expectStreamedForward() {
        upstreamClient.when(request().withPath("/streamedForward")).respondWithSse(sseResponse()
            .withCloseConnection(false)
            .withEvents(
                sseEvent().withData("first"),
                sseEvent().withData("second").withDelay(TimeUnit.MILLISECONDS, 100),
                sseEvent().withData("last").withDelay(TimeUnit.MILLISECONDS, 100)));
        mockServerClient.when(request().withPath("/streamedForward")).withId("streamed-forward").forward(forward().withHost("127.0.0.1").withPort(upstreamServer.getLocalPort()));
    }

    // delayed chunks

    @Test
    public void delayedChunksOverHttp1() throws Exception {
        expectDelayedChunks();
        assertCorpus("delayed-chunks-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(get("/delayedChunks"));
            client.readResponse();
        }));
    }

    @Test
    public void delayedChunksOverHttp2() throws Exception {
        expectDelayedChunks();
        assertCorpus("delayed-chunks-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("GET", "/delayedChunks"), null, true).awaitEnd()));
    }

    private static void expectDelayedChunks() {
        mockServerClient.when(request().withPath("/delayedChunks")).withId("delayed-chunks").respond(response("0123456789abcdef")
            .withConnectionOptions(connectionOptions().withChunkSize(4).withChunkDelay(delay(TimeUnit.MILLISECONDS, 50))));
    }

    // SSE

    @Test
    public void sseOverHttp1() throws Exception {
        expectSse();
        assertCorpus("sse-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(rawRequest("GET", "/sse", "Accept: text/event-stream"));
            client.readResponse();
        }));
    }

    @Test
    public void sseOverHttp2() throws Exception {
        expectSse();
        assertCorpus("sse-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("GET", "/sse", "accept", "text/event-stream"), null, true).awaitEnd()));
    }

    private static void expectSse() {
        mockServerClient.when(request().withPath("/sse")).withId("sse").respondWithSse(sseResponse()
            .withCloseConnection(false)
            .withEvents(
                sseEvent().withEvent("greeting").withId("1").withData("hello"),
                sseEvent().withData("world").withDelay(TimeUnit.MILLISECONDS, 50)));
    }

    // LLM streaming

    private static final String LLM_PATH = "/v1beta/models/gemini-2.0-flash:streamGenerateContent";
    private static final String LLM_BODY = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Hi\"}]}]}";

    @Test
    public void llmStreamingOverHttp1() throws Exception {
        expectLlmStreaming();
        assertCorpus("llm-streaming-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(rawRequestWithBody("POST", LLM_PATH + "?alt=sse", LLM_BODY, "Content-Type: application/json"));
            client.readResponse();
        }));
    }

    @Test
    public void llmStreamingOverHttp2() throws Exception {
        expectLlmStreaming();
        assertCorpus("llm-streaming-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("POST", LLM_PATH + "?alt=sse", "content-type", "application/json"), LLM_BODY.getBytes(StandardCharsets.UTF_8), true).awaitEnd()));
    }

    // Gemini's stream carries no generated id or clock reading, so it needs no normalisation of its own
    private static void expectLlmStreaming() {
        mockServerClient.when(request().withMethod("POST").withPath(LLM_PATH)).withId("llm-streaming").respondWithLlm(llmResponse()
            .withProvider(Provider.GEMINI)
            .withModel("gemini-2.0-flash")
            .withCompletion(completion()
                .withText("streamed corpus reply")
                .withStreaming(true)
                .withStreamingPhysics(streamingPhysics().withTokensPerSecond(20).withJitter(0.0))));
    }

    // WebSocket

    @Test
    public void webSocketOverHttp1() throws Exception {
        expectWebSocket();
        assertCorpus("websocket-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(rawRequest("GET", "/websocket",
                "Upgrade: websocket",
                "Connection: Upgrade",
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
                "Sec-WebSocket-Version: 13"));
            client.readResponse();
            client.readWebSocketFrame();
        }));
    }

    @Test
    public void webSocketOverHttp2() throws Exception {
        expectWebSocket();
        assertCorpus("websocket-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("GET", "/websocket"), null, true).awaitEnd()));
    }

    private static void expectWebSocket() {
        mockServerClient.when(request().withPath("/websocket")).withId("websocket").respondWithWebSocket(webSocketResponse()
            .withMessage(webSocketMessage("hello over websocket")));
    }

    // CORS preflight

    @Test
    public void corsPreflightOverHttp1() throws Exception {
        assertCorpus("cors-preflight-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(rawRequest("OPTIONS", "/cors",
                "Origin: https://corpus.example",
                "Access-Control-Request-Method: POST",
                "Access-Control-Request-Headers: x-corpus"));
            client.readResponse();
        }));
    }

    @Test
    public void corsPreflightOverHttp2() throws Exception {
        assertCorpus("cors-preflight-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("OPTIONS", "/cors",
                "origin", "https://corpus.example",
                "access-control-request-method", "POST",
                "access-control-request-headers", "x-corpus"), null, true).awaitEnd()));
    }

    // 404

    @Test
    public void notFoundOverHttp1() throws Exception {
        assertCorpus("not-found-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(get("/not-found"));
            client.readResponse();
        }));
    }

    @Test
    public void notFoundOverHttp2() throws Exception {
        assertCorpus("not-found-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("GET", "/not-found"), null, true).awaitEnd()));
    }

    // static file

    @Test
    public void staticFileOverHttp1() throws Exception {
        assertCorpus("static-file-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(get("/static.txt"));
            client.readResponse();
        }));
    }

    @Test
    public void staticFileOverHttp2() throws Exception {
        assertCorpus("static-file-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("GET", "/static.txt"), null, true).awaitEnd()));
    }

    // respondBeforeBody: answered from the head alone over HTTP/1.1; HTTP/2 has no early path, so it sends the body

    @Test
    public void respondBeforeBodyOverHttp1() throws Exception {
        expectRespondBeforeBody();
        assertCorpus("respond-before-body-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(rawRequest("POST", "/upload", "Content-Length: 100000"));
            client.readResponse();
            client.readUntilClosed();
        }));
    }

    @Test
    public void respondBeforeBodyOverHttp2() throws Exception {
        expectRespondBeforeBody();
        assertCorpus("respond-before-body-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("POST", "/upload"), "sixteen bytes!!!".getBytes(StandardCharsets.UTF_8), true).awaitEnd()));
    }

    private static void expectRespondBeforeBody() {
        mockServerClient.when(request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true)).withId("respond-before-body").respond(response()
            .withStatusCode(403)
            .withBody("forbidden")
            .withConnectionOptions(connectionOptions().withCloseSocket(true)));
    }

    // breakpoints

    @Test
    public void breakpointModifyOverHttp1() throws Exception {
        expectBreakpointModify();
        try {
            assertCorpus("breakpoint-modify-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
                client.send(get("/breakpoint/modify"));
                client.readResponse();
            }));
        } finally {
            breakpointClient.clearBreakpointMatchers();
        }
    }

    @Test
    public void breakpointModifyOverHttp2() throws Exception {
        expectBreakpointModify();
        try {
            assertCorpus("breakpoint-modify-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
                client.send(h2Request("GET", "/breakpoint/modify"), null, true).awaitEnd()));
        } finally {
            breakpointClient.clearBreakpointMatchers();
        }
    }

    private static void expectBreakpointModify() {
        mockServerClient.when(request().withPath("/breakpoint/modify")).withId("breakpoint-modify")
            .respond(template(VELOCITY, "{ \"statusCode\": 200, \"body\": \"x-breakpoint=$!request.headers['x-breakpoint'][0]\" }"));
        breakpointClient.addBreakpoint(request().withPath("/breakpoint/modify"), paused -> paused.withHeader("x-breakpoint", "modified"));
    }

    @Test
    public void breakpointAbortOverHttp1() throws Exception {
        expectBreakpointAbort();
        try {
            assertCorpus("breakpoint-abort-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
                client.send(get("/breakpoint/abort"));
                client.readResponse();
            }));
        } finally {
            breakpointClient.clearBreakpointMatchers();
        }
    }

    @Test
    public void breakpointAbortOverHttp2() throws Exception {
        expectBreakpointAbort();
        try {
            assertCorpus("breakpoint-abort-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
                client.send(h2Request("GET", "/breakpoint/abort"), null, true).awaitEnd()));
        } finally {
            breakpointClient.clearBreakpointMatchers();
        }
    }

    private static void expectBreakpointAbort() {
        mockServerClient.when(request().withPath("/breakpoint/abort")).withId("breakpoint-abort").respond(response("never sent"));
        breakpointClient.addBreakpoint(request().withPath("/breakpoint/abort"), paused -> response().withStatusCode(418).withBody("aborted by breakpoint"));
    }

    // error()

    @Test
    public void errorDropOverHttp1() throws Exception {
        mockServerClient.when(request().withPath("/error/drop")).withId("error-drop").error(error().withDropConnection(true));
        assertCorpus("error-drop-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(get("/error/drop"));
            client.readUntilClosed();
        }));
    }

    @Test
    public void errorDropOverHttp2() throws Exception {
        mockServerClient.when(request().withPath("/error/drop")).withId("error-drop").error(error().withDropConnection(true));
        assertCorpus("error-drop-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client -> {
            client.send(h2Request("GET", "/error/drop"), null, true).awaitEndOrRecordStillOpen(QUIET_MILLIS);
        }));
    }

    private static final byte[] RAW_RESPONSE_BYTES = "HTTP/1.1 200 OK\r\ncontent-length: 3\r\n\r\nraw".getBytes(StandardCharsets.US_ASCII);

    @Test
    public void errorRawBytesOverHttp1() throws Exception {
        mockServerClient.when(request().withPath("/error/raw")).withId("error-raw").error(error().withResponseBytes(RAW_RESPONSE_BYTES));
        assertCorpus("error-raw-bytes-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(get("/error/raw"));
            client.readResponse();
        }));
    }

    @Test
    public void errorRawBytesOverHttp2() throws Exception {
        mockServerClient.when(request().withPath("/error/raw")).withId("error-raw").error(error().withResponseBytes(RAW_RESPONSE_BYTES));
        assertCorpus("error-raw-bytes-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("GET", "/error/raw"), null, true).awaitEndOrRecordStillOpen(QUIET_MILLIS)));
    }

    // CONNECT tunnel back into MockServer

    @Test
    public void connectTunnelOverHttp1() throws Exception {
        expectTunnelled();
        assertCorpus("connect-tunnel-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(rawRequest("CONNECT", "127.0.0.1:" + mockServer.getLocalPort()));
            client.readResponse(true);
            client.send(get("/tunnelled"));
            client.readResponse();
        }));
    }

    @Test
    public void connectTunnelOverHttp2() throws Exception {
        expectTunnelled();
        assertCorpus("connect-tunnel-http2", mockServer, normaliser -> {
            try (RecordingHttp2Client client = RecordingHttp2Client.throughConnect(http2Group, mockServer.getLocalPort(), "127.0.0.1:" + mockServer.getLocalPort(), normaliser)) {
                client.send(h2Request("GET", "/tunnelled"), null, true).awaitEnd();
                return client.transcript();
            }
        });
    }

    private static void expectTunnelled() {
        mockServerClient.when(request().withPath("/tunnelled")).withId("tunnelled").respond(response("tunnelled"));
    }

    // data-plane authentication

    @Test
    public void dataPlaneAuthenticationRefusalOverHttp1() throws Exception {
        assertCorpus("auth-401-http1", authServer, normaliser -> overHttp1(authServer, normaliser, client -> {
            client.send(get("/protected"));
            client.readResponse();
        }));
    }

    @Test
    public void dataPlaneAuthenticationRefusalOverHttp2() throws Exception {
        assertCorpus("auth-401-http2", authServer, normaliser -> overHttp2(authServer, normaliser, client ->
            client.send(h2Request("GET", "/protected"), null, true).awaitEnd()));
    }

    // a control-plane path no route serves falls through to matching

    @Test
    public void unservedControlPlanePathWithACatchAllOverHttp1() throws Exception {
        mockServerClient.when(request()).withId("catch-all").respond(response("catch-all"));
        assertCorpus("unserved-control-plane-path-http1", mockServer, normaliser -> overHttp1(mockServer, normaliser, client -> {
            client.send(get("/mockserver/x"));
            client.readResponse();
        }));
    }

    @Test
    public void unservedControlPlanePathWithACatchAllOverHttp2() throws Exception {
        mockServerClient.when(request()).withId("catch-all").respond(response("catch-all"));
        assertCorpus("unserved-control-plane-path-http2", mockServer, normaliser -> overHttp2(mockServer, normaliser, client ->
            client.send(h2Request("GET", "/mockserver/x"), null, true).awaitEnd()));
    }

    // harness

    private interface Exchange<C> {
        void run(C client) throws Exception;
    }

    private interface Scenario {
        String wireTranscript(CorpusNormaliser normaliser) throws Exception;
    }

    private static String overHttp1(MockServer server, CorpusNormaliser normaliser, Exchange<RecordingHttp1Client> exchange) throws Exception {
        try (RecordingHttp1Client client = RecordingHttp1Client.connect(server.getLocalPort(), normaliser)) {
            exchange.run(client);
            return client.transcript();
        }
    }

    private static String overHttp2(MockServer server, CorpusNormaliser normaliser, Exchange<RecordingHttp2Client> exchange) throws Exception {
        try (RecordingHttp2Client client = RecordingHttp2Client.h2c(http2Group, server.getLocalPort(), normaliser)) {
            exchange.run(client);
            return client.transcript();
        }
    }

    private static void assertCorpus(String name, MockServer server, Scenario scenario) throws Exception {
        assertCorpus(name, server, false, scenario);
    }

    private static void assertCorpus(String name, MockServer server, boolean groupLogByRequest, Scenario scenario) throws Exception {
        MockServerEventLog eventLog = EventLogTestAccess.eventLog(server);
        eventLog.reset();

        CorpusNormaliser normaliser = new CorpusNormaliser()
            .knownPort(mockServer.getLocalPort(), "mockserver")
            .knownPort(upstreamServer.getLocalPort(), "upstream")
            .knownPort(authServer.getLocalPort(), "auth-server");
        String wire = scenario.wireTranscript(normaliser);
        String serialisedLog = settledLog(server, eventLog, groupLogByRequest);

        String document = "# data-plane differential corpus: " + name + "\n"
            + "== wire ==\n" + wire
            + "== event log ==\n" + serialisedLog + "\n";
        String actual = normaliser.normaliseDocument(document, CorpusNormaliser.correlationIds(serialisedLog));
        int controlCharacter = CorpusNormaliser.firstControlCharacter(actual);
        if (controlCharacter >= 0) {
            // git rewrites CR and LF in text files, so a golden must hold every other control byte escaped
            fail("scenario " + name + " has an unescaped control character at " + controlCharacter + ": " + actual.substring(Math.max(0, controlCharacter - 80), controlCharacter));
        }

        Path golden = basedir().resolve(GOLDEN_DIRECTORY).resolve(name + ".txt");
        if (Boolean.getBoolean(CorpusNormaliser.REGENERATE_PROPERTY)) {
            Files.createDirectories(golden.getParent());
            Files.write(golden, actual.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!Files.exists(golden)) {
            fail("no golden file " + golden + ", run with -D" + CorpusNormaliser.REGENERATE_PROPERTY + "=true to record one");
        }
        String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            Path actualFile = basedir().resolve("target/corpus-actual").resolve(name + ".txt");
            Files.createDirectories(actualFile.getParent());
            Files.write(actualFile, actual.getBytes(StandardCharsets.UTF_8));
            assertEquals("scenario " + name + " differs from " + golden + " (received: " + actualFile + ")", expected, actual);
        }
    }

    private static Path basedir() {
        return Paths.get(System.getProperty("project.basedir", System.getProperty("user.dir")));
    }

    /**
     * The serialised entries once the log stops changing: some are logged after the response is written.
     */
    private static String settledLog(LifeCycle server, MockServerEventLog eventLog, boolean groupByRequest) throws Exception {
        LogEntrySerializer serializer = new LogEntrySerializer(new MockServerLogger(), server.getConfiguration());
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        String previous = null;
        int stablePolls = 0;
        while (System.currentTimeMillis() < deadline) {
            CompletableFuture<List<LogEntry>> entries = new CompletableFuture<>();
            eventLog.retrieveMessageLogEntries(null, entries::complete);
            List<LogEntry> retrieved = entries.get(10, TimeUnit.SECONDS);
            String current = serializer.serialize(groupByRequest ? groupedByRequest(retrieved) : retrieved);
            if (current.equals(previous)) {
                if (++stablePolls >= LOG_SETTLE_STABLE_POLLS) {
                    return current;
                }
            } else {
                stablePolls = 0;
                previous = current;
            }
            TimeUnit.MILLISECONDS.sleep(LOG_SETTLE_POLL_MILLIS);
        }
        throw new AssertionError("the event log did not settle within 10 seconds, last read: " + previous);
    }

    /**
     * Each request's entries together, in their logged order, with the requests ordered by path.
     */
    private static List<LogEntry> groupedByRequest(List<LogEntry> entries) {
        Map<String, List<LogEntry>> byCorrelationId = new LinkedHashMap<>();
        for (LogEntry entry : entries) {
            byCorrelationId.computeIfAbsent(String.valueOf(entry.getCorrelationId()), id -> new ArrayList<>()).add(entry);
        }
        List<List<LogEntry>> groups = new ArrayList<>(byCorrelationId.values());
        groups.sort(Comparator.comparing(NettyDataPlaneDifferentialCorpusIntegrationTest::requestPath));
        List<LogEntry> grouped = new ArrayList<>();
        groups.forEach(grouped::addAll);
        return grouped;
    }

    private static String requestPath(List<LogEntry> group) {
        for (LogEntry entry : group) {
            RequestDefinition[] requests = entry.getHttpRequests();
            if (requests != null && requests.length > 0 && requests[0] instanceof HttpRequest && ((HttpRequest) requests[0]).getPath() != null) {
                return ((HttpRequest) requests[0]).getPath().getValue();
            }
        }
        return "";
    }
}

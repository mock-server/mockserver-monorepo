package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.RequestExplanation;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.log.model.LogEntry;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.socket.PortFactory;
import org.slf4j.event.Level;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;

/**
 * End to end: a request whose forward to a closed port failed is explained, over {@code PUT /mockserver/explain}, the
 * MCP {@code explain_request} tool and the typed Java client, as what happened when it arrived: it matched the forward
 * expectation (or, with no expectation, was proxied) and the forward failed, with the reason the 502 gave.
 */
public class ExplainFailedForwardArrivalIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    private static ClientAndServer mockServer;

    @BeforeClass
    public static void startServer() {
        // INFO, the default outside tests: below it MockServer records no forward failure
        mockServer = ClientAndServer.startClientAndServer(configuration().logLevel(Level.INFO), 0);
    }

    @AfterClass
    public static void stopServer() {
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    public void aFailedForwardIsExplainedAsMatchedWithTheForwardFailureNotAsNoMatch() throws Exception {
        mockServer.when(request().withMethod("GET").withPath("/failed-forward/stock"))
            .withId("stock-forward")
            .forward(forward().withHost("127.0.0.1").withPort(PortFactory.findFreePort()));
        HttpResponse<String> forwarded = get(mockServer, "/failed-forward/stock");
        assertThat(forwarded.statusCode(), is(502));

        String correlationId = failedForwardCorrelationId(mockServer, "/failed-forward/stock");
        JsonNode result = explain(mockServer, correlationId);

        JsonNode arrival = result.path("atArrival");
        assertThat(result.toString(), arrival.path("outcome").asText(), is("MATCHED"));
        assertThat(arrival.path("expectationId").asText(), is("stock-forward"));
        assertThat(arrival.path("expectationStillStored").asBoolean(), is(true));
        assertThat(result.toString(), result.path("sameOutcomeAsAtArrival").asBoolean(), is(true));
        JsonNode failure = arrival.path("forwardFailure");
        assertThat(result.toString(), failure.path("statusCode").asInt(), is(502));
        assertThat(failure.path("reason").asText(), containsString("upstream refused the connection"));
        assertThat(failure.path("reason").asText(), is(forwarded.body()));
        assertThat(failure.path("causeNamed").asBoolean(), is(true));

        JsonNode overMcp = callExplainRequestTool(mockServer, correlationId);
        assertThat(overMcp.toString(), overMcp.path("atArrival").path("outcome").asText(), is("MATCHED"));
        assertThat(overMcp.path("atArrival").path("expectationId").asText(), is("stock-forward"));
        assertThat(overMcp.path("atArrival").path("forwardFailure").path("reason").asText(), is(forwarded.body()));

        RequestExplanation typed = mockServer.explain(correlationId);
        assertThat(typed.atArrival().outcome(), is(RequestExplanation.Outcome.MATCHED));
        assertThat(typed.atArrival().expectationId(), is("stock-forward"));
        assertThat(typed.sameOutcomeAsAtArrival(), is(true));
    }

    @Test
    public void aGenuineMissIsStillExplainedAsNoMatchWithoutAForwardFailure() throws Exception {
        assertThat(get(mockServer, "/failed-forward/never-mocked").statusCode(), is(404));

        JsonNode result = explain(mockServer, correlationIdOf(mockServer, "/failed-forward/never-mocked", LogEntry.LogMessageType.NO_MATCH_RESPONSE));

        assertThat(result.toString(), result.path("atArrival").path("outcome").asText(), is("NO_MATCH"));
        assertThat(result.path("atArrival").has("forwardFailure"), is(false));
    }

    @Test
    public void aFailedProxyOfAnUnmatchedRequestIsExplainedAsForwardedWithoutAnExpectation() throws Exception {
        ClientAndServer proxying = ClientAndServer.startClientAndServer(configuration().logLevel(Level.INFO)
            .proxyRemoteHost("127.0.0.1").proxyRemotePort(PortFactory.findFreePort()), 0);
        try {
            HttpResponse<String> proxied = get(proxying, "/failed-proxy/orders");
            assertThat(proxied.statusCode(), is(502));

            JsonNode result = explain(proxying, failedForwardCorrelationId(proxying, "/failed-proxy/orders"));

            JsonNode arrival = result.path("atArrival");
            assertThat(result.toString(), arrival.path("outcome").asText(), is("FORWARDED_WITHOUT_EXPECTATION"));
            assertThat(arrival.has("expectationId"), is(false));
            assertThat(result.toString(), result.path("sameOutcomeAsAtArrival").asBoolean(), is(true));
            assertThat(arrival.path("forwardFailure").path("statusCode").asInt(), is(502));
            assertThat(arrival.path("forwardFailure").path("reason").asText().isEmpty(), is(false));
        } finally {
            proxying.stop();
        }
    }

    private static HttpResponse<String> get(ClientAndServer server, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getLocalPort() + path))
            .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String failedForwardCorrelationId(ClientAndServer server, String path) throws InterruptedException {
        return correlationIdOf(server, path, LogEntry.LogMessageType.NO_MATCH_RESPONSE);
    }

    private static String correlationIdOf(ClientAndServer server, String path, LogEntry.LogMessageType type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String correlationId = Arrays.stream(server.retrieveLogEntries(request().withPath(path)))
                .filter(entry -> entry.getType() == type)
                .map(LogEntry::getCorrelationId)
                .findFirst().orElse(null);
            if (correlationId != null) {
                return correlationId;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no " + type + " entry was recorded for " + path);
    }

    private static JsonNode explain(ClientAndServer server, String correlationId) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/mockserver/explain?correlationId=" + correlationId))
            .timeout(Duration.ofSeconds(30)).PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.body(), response.statusCode(), is(200));
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static JsonNode callExplainRequestTool(ClientAndServer server, String correlationId) throws Exception {
        HttpResponse<String> initialize = postMcp(server, null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
        String sessionId = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
        postMcp(server, sessionId, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        ObjectNode call = OBJECT_MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call");
        call.putObject("params").put("name", "explain_request").putObject("arguments").put("correlationId", correlationId);
        HttpResponse<String> response = postMcp(server, sessionId, OBJECT_MAPPER.writeValueAsString(call));
        JsonNode result = OBJECT_MAPPER.readTree(response.body()).path("result");
        assertThat(response.body(), result.path("isError").asBoolean(), is(false));
        return OBJECT_MAPPER.readTree(result.path("content").get(0).path("text").asText());
    }

    private static HttpResponse<String> postMcp(ClientAndServer server, String sessionId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/mockserver/mcp"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.openapi.OpenAPIParser;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Every entry point that loads an OpenAPI spec, given a spec URL served by the same MockServer.
 *
 * <p>The server runs a single event loop, so the server's own fetch of the spec is served by the loop that received
 * the request naming it: a fetch made on that loop cannot be answered until it times out. Each call must complete
 * well inside the client timeout instead.
 */
public class OpenApiSelfServedSpecFetchIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);
    private static final String SPEC = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Self Served\",\"version\":\"1.0.0\"}," +
        "\"paths\":{\"/self-served/pets\":{\"get\":{\"operationId\":\"listPets\",\"responses\":{\"200\":{\"description\":\"ok\"," +
        "\"content\":{\"application/json\":{\"schema\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"example\":[{\"id\":1}]}}}}}}}}";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static int port;
    private static HttpClient httpClient;
    private static HttpServer upstream;

    @BeforeClass
    public static void startServers() throws Exception {
        mockServer = new MockServer(configuration().nioEventLoopThreadCount(1).openAPIResponseValidation(true));
        port = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("localhost", port);
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getByName("localhost"), 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = "[{\"id\":2}]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        upstream.start();
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
        mockServerClient.when(request().withPath("/self-spec/.*")).respond(response().withHeader("Content-Type", "application/json").withBody(SPEC));
    }

    /**
     * A URL no earlier test has fetched, so the spec cache cannot answer it.
     */
    private static String freshSpecUrl() {
        return "http://localhost:" + port + "/self-spec/" + UUID.randomUUID() + "/openapi.json";
    }

    @Test
    public void shouldCreateExpectationWithOpenAPIMatcher() throws Exception {
        HttpResponse<String> created = put("/mockserver/expectation", "{\"httpRequest\":{\"specUrlOrPayload\":\"" + freshSpecUrl() + "\",\"operationId\":\"listPets\"}," +
            "\"httpResponse\":{\"statusCode\":200,\"headers\":{\"Content-Type\":[\"application/json\"]},\"body\":[{\"id\":3}]}}");

        assertThat(created.body(), created.statusCode(), is(201));
        assertThat(get("/self-served/pets").statusCode(), is(200));
    }

    @Test
    public void shouldVerifyWithOpenAPIMatcher() throws Exception {
        assertThat(get("/self-served/pets").statusCode(), is(404));
        assertThat(get("/not-in-the-spec").statusCode(), is(404));

        HttpResponse<String> verified = put("/mockserver/verify", "{\"httpRequest\":{\"specUrlOrPayload\":\"" + freshSpecUrl() + "\",\"operationId\":\"listPets\"}," +
            "\"times\":{\"atLeast\":1,\"atMost\":1}}");

        assertThat(verified.body(), verified.statusCode(), is(202));
    }

    @Test
    public void shouldVerifySequenceWithOpenAPIMatcher() throws Exception {
        assertThat(get("/self-served/pets").statusCode(), is(404));

        HttpResponse<String> verified = put("/mockserver/verifySequence", "{\"httpRequests\":[{\"specUrlOrPayload\":\"" + freshSpecUrl() + "\",\"operationId\":\"listPets\"}]}");

        assertThat(verified.body(), verified.statusCode(), is(202));
    }

    @Test
    public void shouldRetrieveWithOpenAPIMatcher() throws Exception {
        assertThat(get("/self-served/pets").statusCode(), is(404));

        HttpResponse<String> retrieved = put("/mockserver/retrieve?type=REQUESTS&format=JSON", "{\"specUrlOrPayload\":\"" + freshSpecUrl() + "\",\"operationId\":\"listPets\"}");

        assertThat(retrieved.body(), retrieved.statusCode(), is(200));
        assertThat(OBJECT_MAPPER.readTree(retrieved.body()).size(), is(1));
    }

    @Test
    public void shouldClearWithOpenAPIMatcher() throws Exception {
        assertThat(get("/self-served/pets").statusCode(), is(404));

        HttpResponse<String> cleared = put("/mockserver/clear?type=LOG", "{\"specUrlOrPayload\":\"" + freshSpecUrl() + "\",\"operationId\":\"listPets\"}");

        assertThat(cleared.body(), cleared.statusCode(), is(200));
        HttpResponse<String> retrieved = put("/mockserver/retrieve?type=REQUESTS&format=JSON", "{\"path\":\"/self-served/pets\"}");
        assertThat(OBJECT_MAPPER.readTree(retrieved.body()).size(), is(0));
    }

    @Test
    public void shouldRegisterBreakpointWithOpenAPIMatcher() throws Exception {
        HttpResponse<String> registered = put("/mockserver/breakpoint/matcher", "{\"httpRequest\":{\"specUrlOrPayload\":\"" + freshSpecUrl() + "\",\"operationId\":\"listPets\"}," +
            "\"phases\":[\"REQUEST\"],\"clientId\":\"self-served-" + UUID.randomUUID() + "\"}");

        assertThat(registered.body(), registered.statusCode(), is(201));
        HttpResponse<String> removed = put("/mockserver/breakpoint/matcher/remove", "{\"id\":\"" + OBJECT_MAPPER.readTree(registered.body()).path("id").asText() + "\"}");
        assertThat(removed.body(), removed.statusCode(), is(200));
    }

    @Test
    public void shouldPromoteRecordingsWithOpenAPIFilter() throws Exception {
        HttpResponse<String> promoted = put("/mockserver/recordings/promote", "{\"specUrlOrPayload\":\"" + freshSpecUrl() + "\",\"operationId\":\"listPets\"}");

        assertThat(promoted.body(), promoted.statusCode(), is(201));
    }

    @Test
    public void shouldGenerateLoadScenarioFromOpenAPI() throws Exception {
        ObjectNode body = OBJECT_MAPPER.createObjectNode()
            .put("name", "self-served-" + UUID.randomUUID())
            .put("specUrlOrPayload", freshSpecUrl());
        body.putObject("target").put("host", "localhost").put("port", port).put("scheme", "http");

        HttpResponse<String> generated = put("/mockserver/loadScenario/generateFromOpenAPI", OBJECT_MAPPER.writeValueAsString(body));

        assertThat(generated.body(), generated.statusCode(), is(200));
        assertThat(OBJECT_MAPPER.readTree(generated.body()).path("status").asText(), is("loaded"));
    }

    @Test
    public void shouldRunContractTest() throws Exception {
        mockServerClient.when(request().withPath("/self-served/pets")).respond(response().withHeader("Content-Type", "application/json").withBody("[{\"id\":4}]"));

        HttpResponse<String> report = put("/mockserver/contractTest", "{\"spec\":\"" + freshSpecUrl() + "\",\"baseUrl\":\"http://localhost:" + port + "\"}");

        assertThat(report.body(), report.statusCode(), is(200));
        JsonNode reportNode = OBJECT_MAPPER.readTree(report.body());
        assertThat(report.body(), reportNode.path("totalOperations").asInt(), is(1));
        assertThat(report.body(), reportNode.path("allPassed").asBoolean(), is(true));
    }

    @Test
    public void shouldValidateRecordedTraffic() throws Exception {
        mockServerClient.when(request().withPath("/self-served/pets")).respond(response().withHeader("Content-Type", "application/json").withBody("[{\"id\":5}]"));
        assertThat(get("/self-served/pets").statusCode(), is(200));

        HttpResponse<String> report = put("/mockserver/trafficValidate", "{\"spec\":\"" + freshSpecUrl() + "\"}");

        assertThat(report.body(), report.statusCode(), is(200));
    }

    @Test
    public void shouldForwardAndValidateRequestAgainstSpec() throws Exception {
        createForwardValidateExpectation(true, false);

        HttpResponse<String> forwarded = get("/self-served/pets");

        assertThat(forwarded.body(), forwarded.statusCode(), is(200));
        assertThat(forwarded.body(), containsString("\"id\":2"));
    }

    @Test
    public void shouldForwardAndValidateResponseAgainstSpec() throws Exception {
        createForwardValidateExpectation(false, true);

        HttpResponse<String> forwarded = get("/self-served/pets");

        assertThat(forwarded.body(), forwarded.statusCode(), is(200));
        assertThat(forwarded.body(), containsString("\"id\":2"));
    }

    @Test
    public void shouldValidateMockResponseWhenTheSpecMustBeFetchedAgain() throws Exception {
        String specUrl = freshSpecUrl();
        HttpResponse<String> imported = put("/mockserver/openapi", "{\"specUrlOrPayload\":\"" + specUrl + "\"}");
        assertThat(imported.body(), imported.statusCode(), is(201));
        OpenAPIParser.clearCache(specUrl);

        HttpResponse<String> served = get("/self-served/pets");

        assertThat(served.body(), served.statusCode(), is(200));
    }

    private void createForwardValidateExpectation(boolean validateRequest, boolean validateResponse) throws Exception {
        ObjectNode expectation = OBJECT_MAPPER.createObjectNode();
        expectation.putObject("httpRequest").put("path", "/self-served/pets");
        expectation.putObject("httpForwardValidateAction")
            .put("specUrlOrPayload", freshSpecUrl())
            .put("host", "localhost")
            .put("port", upstream.getAddress().getPort())
            .put("scheme", "HTTP")
            .put("validateRequest", validateRequest)
            .put("validateResponse", validateResponse)
            .put("validationMode", "STRICT");
        HttpResponse<String> created = put("/mockserver/expectation", OBJECT_MAPPER.writeValueAsString(expectation));
        assertThat(created.body(), created.statusCode(), is(201));
    }

    private static HttpResponse<String> put(String pathAndQuery, String body) throws Exception {
        return httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .timeout(CALL_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(CALL_TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }
}

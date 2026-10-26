package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.netty.integration.NoDependenciesJarRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Smoke test that boots the executable {@code mockserver-netty-no-dependencies}
 * jar via {@code java -jar} and verifies it serves mocked responses end-to-end.
 *
 * <p>Intentionally uses only the JDK ({@link java.net.http.HttpClient}) so the
 * test JVM never loads {@code MockServerClient} / {@code NettyHttpClient} — if
 * it did, the shaded jar (which contains those classes with {@code shaded_packa
 * ge.io.netty.*} parameter types) would clash with the unshaded
 * {@code mockserver-core} on the test classpath.
 *
 * <p>What this guards against:
 * <ul>
 *   <li>{@code Main-Class} manifest entry missing or wrong (jar would fail to launch)
 *   <li>CLI argument parser regression (the {@code -serverPort} arg is rejected)
 *   <li>Shaded jar missing classes needed for boot (e.g. shade-plugin filter mistake)
 *   <li>Shaded jar missing an SLF4J logging backend — the server runs but logs
 *       nothing because SLF4J falls back to a no-op logger (issue #2097)
 *   <li>Port binding / Netty pipeline regression in the shaded build
 *   <li>REST API regression on expectation create or mocked GET
 * </ul>
 */
public class ExtendedNoDependenciesJarMockingIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    // {"hello":"world"} as a zstd frame, written by the zstd CLI so the test needs no zstd library of its own
    private static final byte[] ZSTD_HELLO_WORLD = Base64.getDecoder().decode("KLUv/SQRiQAAeyJoZWxsbyI6IndvcmxkIn2OI6ZS");

    private static NoDependenciesJarRunner runner;
    private static int port;

    @BeforeClass
    public static void startServer() {
        port = findFreePort();
        runner = NoDependenciesJarRunner.startServerUsingNoDependenciesJar(port);
    }

    @AfterClass
    public static void stopServer() {
        if (runner != null) {
            runner.stop();
        }
    }

    @Test
    public void shouldServeMockedExpectationFromForkedJar() throws Exception {
        HttpRequest createExpectation = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/expectation"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(
                "{" +
                "  \"httpRequest\": { \"method\": \"GET\", \"path\": \"/smoke-test/hello\" }," +
                "  \"httpResponse\": {" +
                "    \"statusCode\": 200," +
                "    \"headers\": { \"X-Smoke\": [\"ok\"] }," +
                "    \"body\": \"smoke-test-response\"" +
                "  }" +
                "}"))
            .build();
        HttpResponse<String> createResponse = HTTP.send(createExpectation, HttpResponse.BodyHandlers.ofString());
        assertEquals(
            "PUT /mockserver/expectation returned " + createResponse.statusCode() + " body=" + createResponse.body(),
            201, createResponse.statusCode());

        HttpRequest invokeMock = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/smoke-test/hello"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        HttpResponse<String> mockedResponse = HTTP.send(invokeMock, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, mockedResponse.statusCode());
        assertEquals("smoke-test-response", mockedResponse.body());
        assertEquals("ok", mockedResponse.headers().firstValue("X-Smoke").orElse(null));
    }

    @Test
    public void shouldReportStatusOk() throws Exception {
        HttpRequest status = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/status"))
            .timeout(Duration.ofSeconds(5))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> response = HTTP.send(status, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        // Body is JSON like {"ports":[<port>]} — assert it mentions the port we bound to.
        assertTrue("status body did not contain port: " + response.body(),
            response.body().contains(Integer.toString(port)));
    }

    /**
     * Issue #2772: JSON body matching runs json-unit, whose Diff references opentest4j; both are relocated.
     */
    @Test
    public void shouldMatchJsonBodiesOnlyWhenTheyAreEquivalent() throws Exception {
        HttpRequest createExpectation = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/expectation"))
            .timeout(Duration.ofSeconds(10))
            .PUT(HttpRequest.BodyPublishers.ofString("{" +
                "  \"httpRequest\": { \"method\": \"POST\", \"path\": \"/smoke-test/json\"," +
                "    \"body\": { \"type\": \"JSON\", \"json\": { \"name\": \"smoke\", \"size\": 2 }, \"matchType\": \"STRICT\" } }," +
                "  \"httpResponse\": { \"statusCode\": 200, \"body\": \"json-matched\" }" +
                "}"))
            .build();
        HttpResponse<String> created = HTTP.send(createExpectation, HttpResponse.BodyHandlers.ofString());
        assertEquals("PUT /mockserver/expectation returned " + created.statusCode() + " body=" + created.body(),
            201, created.statusCode());

        HttpResponse<String> equivalent = HTTP.send(jsonPost("{ \"size\": 2, \"name\": \"smoke\" }"), HttpResponse.BodyHandlers.ofString());
        assertEquals("an equivalent JSON body (other field order) must match: " + equivalent.body(), 200, equivalent.statusCode());
        assertEquals("json-matched", equivalent.body());

        HttpResponse<String> different = HTTP.send(jsonPost("{ \"name\": \"smoke\", \"size\": 3 }"), HttpResponse.BodyHandlers.ofString());
        assertEquals("a JSON body with a different value must not match: " + different.body(), 404, different.statusCode());
    }

    private static HttpRequest jsonPost(String body) {
        return HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/smoke-test/json"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    /**
     * Issue #2772: relocating GraphQL Java with a one-segment pattern also rewrote MockServer's own
     * "/graphql" endpoint literal (and its "graphql-ws" subprotocols) to "shaded_package/graphql".
     */
    @Test
    public void shouldServeGraphQLResponsesGeneratedFromASchema() throws Exception {
        HttpRequest importSchema = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/graphql"))
            .timeout(Duration.ofSeconds(10))
            .PUT(HttpRequest.BodyPublishers.ofString("type Query { smokeTest: String }"))
            .build();
        HttpResponse<String> importResponse = HTTP.send(importSchema, HttpResponse.BodyHandlers.ofString());
        assertEquals("PUT /mockserver/graphql body=" + importResponse.body(), 201, importResponse.statusCode());

        HttpRequest query = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/graphql"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"query\": \"{ smokeTest }\"}"))
            .build();
        HttpResponse<String> response = HTTP.send(query, HttpResponse.BodyHandlers.ofString());
        assertEquals("POST /graphql body=" + response.body(), 200, response.statusCode());
        assertTrue("expected a synthesised smokeTest field, got: " + response.body(),
            response.body().contains("\"smokeTest\""));
    }

    /**
     * Issue #2869: zstd-jni's natives bind only to its unrelocated classes, so in a jar that relocated it a zstd
     * request closed the connection with UnsatisfiedLinkError. The body only matches once it is decoded.
     */
    @Test
    public void shouldDecodeZstdEncodedRequestBodies() throws Exception {
        createExpectation("{" +
            "  \"httpRequest\": { \"method\": \"POST\", \"path\": \"/smoke-test/zstd\"," +
            "    \"body\": { \"type\": \"JSON\", \"json\": { \"hello\": \"world\" }, \"matchType\": \"STRICT\" } }," +
            "  \"httpResponse\": { \"statusCode\": 200, \"body\": \"zstd-decoded\" }" +
            "}");

        HttpResponse<String> response = HTTP.send(zstdPost("/smoke-test/zstd"), HttpResponse.BodyHandlers.ofString());

        assertEquals("a zstd-encoded body must be decoded and matched: " + response.body(), 200, response.statusCode());
        assertEquals("zstd-decoded", response.body());
    }

    /**
     * Issue #2869: forwarding a zstd request whose body an override replaced re-encodes it with zstd-jni, which the
     * upstream (this server again) must decode to match.
     */
    @Test
    public void shouldReEncodeAForwardedZstdRequestBody() throws Exception {
        createExpectation("{" +
            "  \"httpRequest\": { \"method\": \"POST\", \"path\": \"/smoke-test/zstd-upstream\"," +
            "    \"body\": { \"type\": \"JSON\", \"json\": { \"hello\": \"override\" }, \"matchType\": \"STRICT\" } }," +
            "  \"httpResponse\": { \"statusCode\": 200, \"body\": \"zstd-forwarded\" }" +
            "}");
        createExpectation("{" +
            "  \"httpRequest\": { \"method\": \"POST\", \"path\": \"/smoke-test/zstd-forward\" }," +
            "  \"httpOverrideForwardedRequest\": { \"requestOverride\": {" +
            "    \"path\": \"/smoke-test/zstd-upstream\", \"headers\": { \"Host\": [\"localhost:" + port + "\"] }," +
            "    \"body\": { \"type\": \"JSON\", \"json\": { \"hello\": \"override\" } } } }" +
            "}");

        HttpResponse<String> response = HTTP.send(zstdPost("/smoke-test/zstd-forward"), HttpResponse.BodyHandlers.ofString());

        assertEquals("the forwarded body must be zstd-encoded and decoded upstream: " + response.body(), 200, response.statusCode());
        assertEquals("zstd-forwarded", response.body());
    }

    private static void createExpectation(String json) throws Exception {
        HttpRequest create = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/expectation"))
            .timeout(Duration.ofSeconds(10))
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> created = HTTP.send(create, HttpResponse.BodyHandlers.ofString());
        assertEquals("PUT /mockserver/expectation returned " + created.statusCode() + " body=" + created.body(), 201, created.statusCode());
    }

    private static HttpRequest zstdPost(String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "zstd")
            .POST(HttpRequest.BodyPublishers.ofByteArray(ZSTD_HELLO_WORLD))
            .build();
    }

    /**
     * Regression test for issue #2097: the shaded no-dependencies jar must
     * bundle an SLF4J logging backend. Without one, SLF4J silently falls back
     * to a no-op logger — the server runs but produces no logs at all, which
     * is exactly the broken behaviour the Docker image shipped.
     *
     * <p>This asserts the observable behaviour — the forked jar boots and
     * actually logs — rather than the jar's internal structure, so it stays
     * valid regardless of which SLF4J binding or discovery mechanism is used.
     */
    @Test
    public void shouldBootAndLogWithoutSlf4jProviderFailure() {
        // The startup banner is logged at INFO; on a correctly built jar it
        // appears within milliseconds of the server becoming ready. On a jar
        // with no SLF4J backend it never appears, because logging is a no-op.
        boolean loggedStartupBanner = runner.awaitOutputContaining("started on port", Duration.ofSeconds(20));
        String output = runner.getOutput();

        // any SLF4J(...) line means the bundled provider was not selected quietly: missing (#2097), or found
        // by ServiceLoader beside another, or reported at INFO when chosen through slf4j.provider (#2772)
        assertFalse(
            "SLF4J reported on its provider while booting the no-dependencies jar (issues #2097, #2772):\n" + output,
            output.contains("SLF4J("));

        assertTrue(
            "The no-dependencies jar logged no startup banner — expected an INFO "
                + "'started on port' line; its SLF4J logging backend is not working "
                + "(issue #2097):\n" + output,
            loggedStartupBanner);
    }

    /**
     * Matches the {@code org.mockserver.socket.PortFactory.findFreePort()} pattern
     * used elsewhere in the project: bind an IPv4 socket (never given a port another
     * process listens on at 127.0.0.1) to port 0 to get an OS-assigned ephemeral port,
     * then close and hope the forked JVM grabs it before
     * anyone else does. The window is microseconds; the runner's
     * {@code waitUntilReady} will surface a {@code BindException} as a fast
     * process exit if the race ever loses.
     */
    private static int findFreePort() {
        try (ServerSocketChannel s = ServerSocketChannel.open(StandardProtocolFamily.INET)) {
            s.bind(new InetSocketAddress(0));
            return s.socket().getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not allocate a free port for the test", e);
        }
    }
}

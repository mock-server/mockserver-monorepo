package org.mockserver.examples.mockserver.extensions;

import org.jsoup.Jsoup;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Runs the request filter and response transformer shown on the website's Extensions page
 * ({@code jekyll-www.mock-server.com/mock_server/extensions.html}) in an embedded MockServer, and checks that the page
 * shows each class exactly as it is compiled here, so the page's code cannot drift from code that works.
 * <p>
 * CI runs this only in the mockserver-java pipeline, which a change to the page or to examples/java alone does not
 * trigger; after editing either, run it locally from the repo root:
 * {@code mockserver/mvnw -f examples/java/pom.xml test -Dtest=RequestFilterAndResponseTransformerExampleTest}
 */
public class RequestFilterAndResponseTransformerExampleTest {

    private static final Path BASEDIR = Paths.get(System.getProperty("basedir", "."));
    private static final Path WEBSITE_PAGE = BASEDIR.resolve("../../jekyll-www.mock-server.com/mock_server/extensions.html").normalize();
    private static final Path EXAMPLE_SOURCES = BASEDIR.resolve("src/main/java/org/mockserver/examples/mockserver/extensions");

    private static ClientAndServer mockServer;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeClass
    public static void startMockServer() {
        mockServer = ClientAndServer.startClientAndServer(
            configuration().extensions(ApiKeyRequestFilter.class, ServedByResponseTransformer.class)
        );
    }

    @AfterClass
    public static void stopMockServer() {
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Before
    public void createExpectation() {
        mockServer.reset();
        mockServer.when(request().withPath("/hello")).respond(response().withBody("hello"));
    }

    @Test
    public void shouldAnswerRequestWithoutKeyFromTheFilter() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/hello")).GET());

        assertEquals(401, response.statusCode());
        assertEquals("missing or wrong X-Api-Key", response.body());
        assertEquals("mockserver", response.headers().firstValue("X-Served-By").orElse(null));
    }

    @Test
    public void shouldMatchRequestWithKeyAndRemoveTheKey() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/hello")).header("X-Api-Key", "let-me-in").GET());

        assertEquals(200, response.statusCode());
        assertEquals("hello", response.body());
        assertEquals("mockserver", response.headers().firstValue("X-Served-By").orElse(null));
        org.mockserver.model.HttpRequest[] recorded = mockServer.retrieveRecordedRequests(request().withPath("/hello"));
        assertEquals(1, recorded.length);
        assertFalse(recorded[0].containsHeader("X-Api-Key"));
    }

    @Test
    public void shouldReplaceTheBodyOfTheNoMatchResponseAndSetItsLength() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/missing")).header("X-Api-Key", "let-me-in").GET());

        assertEquals(404, response.statusCode());
        assertEquals("{\"error\": \"no expectation matched this request\"}", response.body());
        assertEquals(String.valueOf(response.body().getBytes(StandardCharsets.UTF_8).length), response.headers().firstValue("Content-Length").orElse(null));
        assertEquals("mockserver", response.headers().firstValue("X-Served-By").orElse(null));
    }

    @Test
    public void shouldLetCorsPreflightThroughWithoutKey() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/hello"))
            .header("Origin", "https://app.example.com")
            .header("Access-Control-Request-Method", "GET")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));

        assertEquals(200, response.statusCode());
        assertEquals("hello", response.body());
    }

    @Test
    public void shouldShowEachExampleOnTheWebsiteAsCompiled() throws IOException {
        assertTrue("website page not found at " + WEBSITE_PAGE, Files.isRegularFile(WEBSITE_PAGE));
        List<String> codeBlocks = Jsoup.parse(Files.readString(WEBSITE_PAGE)).select("pre").stream()
            .map(pre -> withoutPackage(pre.wholeText()))
            .collect(Collectors.toList());
        for (String example : Arrays.asList("ApiKeyRequestFilter.java", "ServedByResponseTransformer.java")) {
            String source = withoutPackage(Files.readString(EXAMPLE_SOURCES.resolve(example)));
            assertTrue(example + " is not shown as it is compiled on " + WEBSITE_PAGE + "; copy it into the page (escaping <, > and &)", codeBlocks.contains(source));
        }
    }

    private static String withoutPackage(String code) {
        return code.replace("\r\n", "\n").replaceFirst("^\\s*package [^;]+;", "").trim();
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + mockServer.getPort() + path);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.OpenAPIDefinition.openAPI;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.VerificationTimes.atLeast;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * A verification whose request matcher is an OpenAPI operation must count only the recorded requests
 * that match that operation, over the REST API and through the Java client.
 */
public class OpenAPIRequestVerificationIntegrationTest {

    private static final String SPEC = "org/mockserver/openapi/openapi_petstore_example.json";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static ClientAndServer mockServer;

    @BeforeClass
    public static void startServerAndRecordRequests() throws Exception {
        mockServer = ClientAndServer.startClientAndServer();
        mockServer.when(request()).respond(response().withStatusCode(200));
        assertThat(send("GET", "/v1/pets?limit=10", null).statusCode(), is(200));
        assertThat(send("GET", "/v1/pets?limit=5", null).statusCode(), is(200));
        assertThat(send("POST", "/v1/pets", "{\"id\":50,\"name\":\"scruffles\",\"tag\":\"dog\"}").statusCode(), is(200));
        assertThat(send("GET", "/not/in/the/spec", null).statusCode(), is(200));
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServer);
    }

    @Test
    public void restVerifyShouldCountOnlyRequestsMatchingTheOperation() throws Exception {
        assertThat(send("PUT", "/mockserver/verify", verificationJson("listPets", 2, 2)).statusCode(), is(202));
        assertThat(send("PUT", "/mockserver/verify", verificationJson("createPets", 1, 1)).statusCode(), is(202));

        HttpResponse<String> tooFew = send("PUT", "/mockserver/verify", verificationJson("listPets", 1, 1));
        assertThat(tooFew.statusCode(), is(406));
        assertThat(tooFew.body(), containsString("Request found 2 times but should have been found exactly once"));
    }

    @Test
    public void restVerifyShouldFailForAnOperationThatWasNeverCalled() throws Exception {
        HttpResponse<String> response = send("PUT", "/mockserver/verify", verificationJson("showPetById", 1, null));

        assertThat(response.statusCode(), is(406));
        assertThat(response.body(), containsString("Request not found at least once"));
    }

    @Test
    public void restVerifyWithoutOperationIdShouldCountOnlyRequestsInTheSpec() throws Exception {
        assertThat(send("PUT", "/mockserver/verify", verificationJson(null, 3, 3)).statusCode(), is(202));
        assertThat(send("PUT", "/mockserver/verify", verificationJson(null, 4, null)).statusCode(), is(406));
    }

    @Test
    public void restVerifySequenceShouldApplyTheOperations() throws Exception {
        assertThat(send("PUT", "/mockserver/verifySequence",
            "{\"httpRequests\":[" + requestJson("listPets") + "," + requestJson("createPets") + "]}"
        ).statusCode(), is(202));
        assertThat(send("PUT", "/mockserver/verifySequence",
            "{\"httpRequests\":[" + requestJson("createPets") + "," + requestJson("listPets") + "]}"
        ).statusCode(), is(406));
    }

    @Test
    public void javaClientVerifyShouldCountOnlyRequestsMatchingTheOperation() {
        mockServer.verify(openAPI(SPEC, "listPets"), exactly(2));
        mockServer.verify(openAPI(SPEC, "createPets"), exactly(1));
        mockServer.verify(openAPI(SPEC), exactly(3));

        AssertionError neverCalled = assertThrows(AssertionError.class,
            () -> mockServer.verify(openAPI(SPEC, "showPetById"), atLeast(1)));
        assertThat(neverCalled.getMessage(), containsString("Request not found at least once"));
        AssertionError wrongCount = assertThrows(AssertionError.class,
            () -> mockServer.verify(openAPI(SPEC, "listPets"), exactly(1)));
        assertThat(wrongCount.getMessage(), containsString("Request found 2 times but should have been found exactly once"));
    }

    @Test
    public void javaClientVerifySequenceShouldApplyTheOperations() {
        mockServer.verify(openAPI(SPEC, "listPets"), openAPI(SPEC, "createPets"));

        AssertionError outOfOrder = assertThrows(AssertionError.class,
            () -> mockServer.verify(openAPI(SPEC, "createPets"), openAPI(SPEC, "listPets")));
        assertThat(outOfOrder.getMessage(), containsString("Request sequence not found"));
    }

    private static String requestJson(String operationId) {
        return "{\"specUrlOrPayload\":\"" + SPEC + "\"" + (operationId != null ? ",\"operationId\":\"" + operationId + "\"" : "") + "}";
    }

    private static String verificationJson(String operationId, int atLeast, Integer atMost) {
        return "{\"httpRequest\":" + requestJson(operationId) + ",\"times\":{\"atLeast\":" + atLeast + (atMost != null ? ",\"atMost\":" + atMost : "") + "}}";
    }

    private static HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + mockServer.getPort() + path))
            .timeout(Duration.ofSeconds(30))
            .method(method, body != null ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
        if (body != null) {
            builder.header("content-type", "application/json");
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

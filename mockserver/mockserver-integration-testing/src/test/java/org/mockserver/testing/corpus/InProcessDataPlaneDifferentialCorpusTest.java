package org.mockserver.testing.corpus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.inprocess.InProcessMockServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.serialization.HttpResponseSerializer;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Differential corpus for {@link InProcessMockServer}, which has no wire: the returned response's status, its headers
 * and trailers in the order the response holds them, its raw body bytes, the response as the REST API serialises it,
 * and the serialised log entries of each scenario, compared byte for byte with the goldens recorded from the
 * baseline. The serialised form alone cannot pin header order, as its serialiser sorts names. See {@link GoldenCorpus}.
 */
public class InProcessDataPlaneDifferentialCorpusTest {

    private static final GoldenCorpus GOLDEN = GoldenCorpus.forSuite(InProcessDataPlaneDifferentialCorpusTest.class);

    private InProcessMockServer mockServer;
    private CorpusLogEntries.Window log;

    @Before
    public void startServer() {
        mockServer = new InProcessMockServer(withDataPlaneHooks(configuration().logLevel("INFO")));
        log = CorpusLogEntries.window(this::retrieveLogEntries);
    }

    /**
     * The server's configuration; a subclass loads extension hooks that change nothing (request filters or
     * response transformers), which must match the same goldens.
     */
    protected Configuration withDataPlaneHooks(Configuration configuration) {
        return configuration;
    }

    @After
    public void stopServer() {
        mockServer.close();
    }

    @Test
    public void mockedResponseKeepsHeaderOrderCaseAndNonAsciiBody() {
        mockServer.upsert(when(request().withMethod("GET").withPath("/corpus/in-process/ordered")).withId("corpus-in-process-ordered").thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("X-Zulu", "z")
                .withHeader("x-alpha", "a")
                .withHeader("X-Multi", "one", "two")
                .withHeader("Content-Type", "text/plain; charset=utf-8")
                .withBody("héllo wörld ✓", StandardCharsets.UTF_8)
        ));

        record("mocked-ordered-headers", request().withMethod("GET").withPath("/corpus/in-process/ordered").withHeader("Accept", "*/*"));
    }

    @Test
    public void mockedResponseWithRequestBodyAndResponseTrailers() {
        mockServer.upsert(when(request().withMethod("POST").withPath("/corpus/in-process/trailers").withBody("ping")).withId("corpus-in-process-trailers").thenRespond(
            response()
                .withStatusCode(201)
                .withReasonPhrase("Made It")
                .withHeader("x-first", "1")
                .withTrailer("x-checksum", "abc=; q=1")
                .withTrailer("x-second-trailer", "2")
                .withBody("pong")
        ));

        record("mocked-trailers", request().withMethod("POST").withPath("/corpus/in-process/trailers").withBody("ping"));
    }

    @Test
    public void mockedBinaryBodyOfEveryByteValue() {
        byte[] everyByte = new byte[256];
        for (int i = 0; i < everyByte.length; i++) {
            everyByte[i] = (byte) i;
        }
        mockServer.upsert(when(request().withMethod("GET").withPath("/corpus/in-process/binary")).withId("corpus-in-process-binary").thenRespond(
            response().withStatusCode(200).withHeader("Content-Type", "application/octet-stream").withBody(everyByte)
        ));

        record("mocked-binary", request().withMethod("GET").withPath("/corpus/in-process/binary"));
    }

    @Test
    public void noMatchingExpectation() {
        record("no-match-404", request().withMethod("GET").withPath("/corpus/in-process/missing"));
    }

    private void record(String scenario, HttpRequest scenarioRequest) {
        HttpResponse returned = mockServer.handle(scenarioRequest);
        byte[] body = returned.getBodyAsRawBytes();
        WireTranscript transcript = new WireTranscript()
            .section("returned status")
            .line(returned.getStatusCode() + " " + returned.getReasonPhrase())
            .section("returned headers, in order")
            .headers(fields(returned.getHeaderList()))
            .section("returned trailers, in order")
            .headers(fields(returned.getTrailerList()))
            .section("returned body bytes")
            .bytes(body != null ? body : new byte[0])
            .section("returned response, serialised (its serialiser sorts header names)")
            .bytes(new HttpResponseSerializer(new MockServerLogger()).serialize(returned).getBytes(StandardCharsets.UTF_8));
        CorpusLogEntries.appendTo(transcript, log.entries(GOLDEN.expectedLogEntries(scenario)));
        GOLDEN.assertMatches(scenario, new CorpusNormaliser().normalise(transcript.toString()));
    }

    private static List<Map.Entry<String, String>> fields(List<Header> headers) {
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        for (Header header : headers) {
            for (NottableString value : header.getValues()) {
                fields.add(new AbstractMap.SimpleEntry<>(header.getName().toString(), value.toString()));
            }
        }
        return fields;
    }

    private String retrieveLogEntries(String requestFilterJson) {
        HttpResponse response = mockServer.handle(request()
            .withMethod("PUT")
            .withPath("/mockserver/retrieve")
            .withQueryStringParameter("type", "LOGS")
            .withQueryStringParameter("format", "LOG_ENTRIES")
            .withBody(requestFilterJson));
        if (response.getStatusCode() != 200) {
            throw new AssertionError("log retrieve answered " + response.getStatusCode() + ": " + response.getBodyAsString());
        }
        return response.getBodyAsString();
    }
}

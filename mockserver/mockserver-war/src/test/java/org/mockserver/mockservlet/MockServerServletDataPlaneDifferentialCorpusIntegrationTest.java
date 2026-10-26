package org.mockserver.mockservlet;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.extension.Extension;
import org.mockserver.testing.corpus.CorpusLogEntries;
import org.mockserver.testing.corpus.CorpusNormaliser;
import org.mockserver.testing.corpus.GoldenCorpus;
import org.mockserver.testing.corpus.WireTranscript;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Differential corpus for the servlet transport (MockServerServlet in embedded Tomcat): the raw HTTP/1.1 response
 * bytes and the serialised log entries of each scenario, compared byte for byte with the goldens recorded from the
 * baseline. See {@link GoldenCorpus} and {@link CorpusNormaliser}.
 */
public class MockServerServletDataPlaneDifferentialCorpusIntegrationTest {

    private static final GoldenCorpus GOLDEN = GoldenCorpus.forSuite(MockServerServletDataPlaneDifferentialCorpusIntegrationTest.class);

    private static Tomcat tomcat;
    private static File baseDir;
    private static int port;
    private static MockServerClient mockServerClient;
    private static Level originalLogLevel;
    private CorpusLogEntries.Window log;

    @BeforeClass
    public static void startServer() throws Exception {
        startServer(null);
    }

    /**
     * @param extensions loaded by the servlet, or {@code null} for none; hooks that change nothing must match the same
     *                   goldens
     */
    @SafeVarargs
    protected static void startServer(Class<? extends Extension>... extensions) throws Exception {
        // the servlet logs some entries (CREATED_EXPECTATION, EXPECTATION_MATCHED, NO_MATCH_RESPONSE) through a logger
        // that reads the global level, not the instance's, so pin it: builds run tests at ERROR locally and INFO in CI
        originalLogLevel = ConfigurationProperties.logLevel();
        ConfigurationProperties.logLevel("INFO");
        baseDir = Files.createTempDirectory("servlet-corpus").toFile();
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.getAbsolutePath());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context context = tomcat.addContext("", baseDir.getAbsolutePath());
        Configuration configuration = configuration().logLevel("INFO").attemptToProxyIfNoMatchingExpectation(false);
        Tomcat.addServlet(context, "mockServerServlet", new MockServerServlet(extensions != null ? configuration.extensions(extensions) : configuration));
        context.addServletMappingDecoded("/*", "mockServerServlet");
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
        mockServerClient = new MockServerClient("127.0.0.1", port);
    }

    @Before
    public void resetAndOpenLogWindow() {
        // the server is shared, so each scenario starts from no expectations: a no-match response names the closest one
        mockServerClient.reset();
        log = CorpusLogEntries.window(CorpusLogEntries.overHttp("http://127.0.0.1:" + port));
    }

    @AfterClass
    public static void stopServer() throws Exception {
        stopQuietly(mockServerClient);
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
        FileUtils.deleteQuietly(baseDir);
        ConfigurationProperties.logLevel(originalLogLevel != null ? originalLogLevel.name() : "OFF");
    }

    @Test
    public void mockedResponseKeepsHeaderOrderCaseAndNonAsciiBody() throws Exception {
        mockServerClient.upsert(when(request().withMethod("GET").withPath("/corpus/servlet/ordered")).withId("corpus-servlet-ordered").thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("X-Zulu", "z")
                .withHeader("x-alpha", "a")
                .withHeader("X-Multi", "one", "two")
                .withHeader("Content-Type", "text/plain; charset=utf-8")
                .withBody("héllo wörld ✓", StandardCharsets.UTF_8)
        ));

        record("mocked-ordered-headers", "GET /corpus/servlet/ordered HTTP/1.1\r\nHost: localhost\r\nAccept: */*\r\nConnection: close\r\n\r\n");
    }

    @Test
    public void mockedResponseToARequestWithABody() throws Exception {
        mockServerClient.upsert(when(request().withMethod("POST").withPath("/corpus/servlet/post").withBody("ping")).withId("corpus-servlet-post").thenRespond(
            response().withStatusCode(201).withHeader("x-first", "1").withReasonPhrase("Made It").withBody("pong")
        ));

        record("mocked-post", "POST /corpus/servlet/post HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nContent-Length: 4\r\nConnection: close\r\n\r\nping");
    }

    @Test
    public void mockedBinaryBodyOfEveryByteValue() throws Exception {
        byte[] everyByte = new byte[256];
        for (int i = 0; i < everyByte.length; i++) {
            everyByte[i] = (byte) i;
        }
        mockServerClient.upsert(when(request().withMethod("GET").withPath("/corpus/servlet/binary")).withId("corpus-servlet-binary").thenRespond(
            response().withStatusCode(200).withHeader("Content-Type", "application/octet-stream").withBody(everyByte)
        ));

        record("mocked-binary", "GET /corpus/servlet/binary HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    }

    @Test
    public void noMatchingExpectation() throws Exception {
        record("no-match-404", "GET /corpus/servlet/missing HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    }

    private void record(String scenario, String rawRequest) throws Exception {
        byte[] responseBytes;
        int clientPort;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
            socket.setSoTimeout(15_000);
            clientPort = socket.getLocalPort();
            OutputStream out = socket.getOutputStream();
            out.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            in.transferTo(received);
            responseBytes = received.toByteArray();
        }
        WireTranscript transcript = new WireTranscript().section("HTTP/1.1 response bytes").bytes(responseBytes);
        CorpusLogEntries.appendTo(transcript, log.entries(GOLDEN.expectedLogEntries(scenario)));
        GOLDEN.assertMatches(scenario, new CorpusNormaliser()
            .ephemeralPort(port, "<server-port>")
            .ephemeralPort(clientPort, "<client-port>")
            .normalise(transcript.toString()));
    }
}

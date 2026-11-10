package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.fixture.SseAwareExpectationConverter;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.netty.MockServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Record and replay the end of an SSE stream (#2947): MockServer forwards a stream whose upstream keeps it open after
 * its last event, the converter turns the recording into an expectation, and the replay ends the stream only after
 * the recorded wait. Timings are tolerant: a stall while the last event is read can shorten a measured wait as well
 * as lengthen it, so each lower bound allows a quarter of a second.
 */
public class SseCloseDelayRecordReplayIntegrationTest {

    private static final String TERMINATING_CHUNK = "0\r\n\r\n";
    private static final long UPSTREAM_GAP_BEFORE_LAST_EVENT_MS = 300;
    private static final long UPSTREAM_OPEN_AFTER_LAST_EVENT_MS = 1500;

    private static ServerSocket upstream;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServers() throws Exception {
        upstream = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> {
            while (!upstream.isClosed()) {
                try {
                    Socket socket = upstream.accept();
                    new Thread(() -> serveStreamThatStaysOpenAfterItsLastEvent(socket)).start();
                } catch (IOException closed) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        mockServer = new MockServer(configuration().streamingResponsesEnabled(true));
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() throws IOException {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        upstream.close();
    }

    private static void serveStreamThatStaysOpenAfterItsLastEvent(Socket socket) {
        try (Socket s = socket) {
            readRequestHead(s.getInputStream());
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            writeChunk(out, "event: a\ndata: 1\n\n");
            Thread.sleep(UPSTREAM_GAP_BEFORE_LAST_EVENT_MS);
            writeChunk(out, "event: b\ndata: 2\n\n");
            Thread.sleep(UPSTREAM_OPEN_AFTER_LAST_EVENT_MS);
            out.write(TERMINATING_CHUNK.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException | InterruptedException ignored) {
            // the test fails on what the client receives
        }
    }

    private static void writeChunk(OutputStream out, String data) throws IOException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void readRequestHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            head.append((char) b);
            if (head.toString().endsWith("\r\n\r\n")) {
                return;
            }
        }
    }

    /** Milliseconds from the arrival of the last event to the arrival of the terminating chunk. */
    private static long millisFromLastEventToEndOfStream(String path) throws IOException {
        try (Socket socket = new Socket("localhost", mockServer.getLocalPort())) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            StringBuilder received = new StringBuilder();
            byte[] buffer = new byte[8192];
            long lastEventNanos = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                received.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                if (lastEventNanos == 0 && received.indexOf("data: 2") >= 0) {
                    lastEventNanos = System.nanoTime();
                }
                if (lastEventNanos != 0 && received.toString().endsWith(TERMINATING_CHUNK)) {
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastEventNanos);
                }
            }
            throw new AssertionError("the stream ended without its last event and terminating chunk:" + received);
        }
    }

    private static Expectation[] recordedExpectations() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        Expectation[] recorded = mockServerClient.retrieveRecordedExpectations(request("/sse"));
        while (recorded.length == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            recorded = mockServerClient.retrieveRecordedExpectations(request("/sse"));
        }
        return recorded;
    }

    @Test(timeout = 60_000)
    public void shouldReplayTheRecordedWaitBeforeTheEndOfTheStream() throws Exception {
        // record: forward to an upstream that keeps the stream open after its last event
        mockServerClient.reset();
        mockServerClient.when(request("/sse")).forward(forward().withHost("127.0.0.1").withPort(upstream.getLocalPort()));
        long recordedGapMs = millisFromLastEventToEndOfStream("/sse");
        assertThat("the forward relays the upstream's wait", recordedGapMs, greaterThanOrEqualTo(UPSTREAM_OPEN_AFTER_LAST_EVENT_MS - 250));

        // convert the recording into an SSE expectation
        Expectation[] recorded = recordedExpectations();
        assertThat(recorded.length, is(1));
        HttpSseResponse sse = new SseAwareExpectationConverter().convert(recorded)[0].getHttpSseResponse();
        assertThat(sse.getEvents().size(), is(2));
        assertThat(sse.getCloseDelay(), notNullValue());
        long closeDelayMs = sse.getCloseDelay().getTimeUnit().toMillis(sse.getCloseDelay().getValue());
        assertThat(closeDelayMs, allOf(greaterThanOrEqualTo(UPSTREAM_OPEN_AFTER_LAST_EVENT_MS - 250), lessThan(UPSTREAM_OPEN_AFTER_LAST_EVENT_MS + 5_000)));

        // replay: the stream ends only after the recorded wait
        mockServerClient.reset();
        mockServerClient.when(request("/sse")).respondWithSse(sse);
        long replayedGapMs = millisFromLastEventToEndOfStream("/sse");
        assertThat(replayedGapMs, allOf(greaterThanOrEqualTo(closeDelayMs - 250), lessThan(closeDelayMs + 10_000)));
    }
}

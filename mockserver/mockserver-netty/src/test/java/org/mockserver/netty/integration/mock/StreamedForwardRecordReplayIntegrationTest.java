package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.fixture.SseAwareExpectationConverter;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.netty.MockServer;
import org.mockserver.serialization.ExpectationSerializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Streamed forwards, scripted byte for byte by a raw socket upstream, are relayed to the client unchanged, recorded
 * with the bytes the client received, and replayed from that recording: as a static response, and as an SSE fixture
 * made by {@link SseAwareExpectationConverter}.
 */
public class StreamedForwardRecordReplayIntegrationTest {

    private static final int MAX_STREAMING_CAPTURE_BYTES = 4096;

    private static final Map<String, List<Write>> SCRIPTS = new ConcurrentHashMap<>();
    private static ServerSocket upstream;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    /** One write of the upstream's script: wait, then send these bytes and flush. */
    private static final class Write {
        private final long delayMs;
        private final byte[] bytes;

        private Write(long delayMs, byte[] bytes) {
            this.delayMs = delayMs;
            this.bytes = bytes;
        }
    }

    @BeforeClass
    public static void startServers() throws Exception {
        upstream = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> {
            while (!upstream.isClosed()) {
                try {
                    Socket socket = upstream.accept();
                    Thread serve = new Thread(() -> serveScript(socket));
                    serve.setDaemon(true);
                    serve.start();
                } catch (IOException closed) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        mockServer = new MockServer(configuration()
            .streamingResponsesEnabled(true)
            .maxStreamingCaptureBytes(MAX_STREAMING_CAPTURE_BYTES));
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() throws IOException {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        upstream.close();
    }

    @Before
    public void reset() {
        mockServerClient.reset();
    }

    private static void serveScript(Socket socket) {
        try (Socket s = socket) {
            String path = readRequestPath(s.getInputStream());
            List<Write> script = SCRIPTS.get(path);
            OutputStream out = s.getOutputStream();
            if (script == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            for (Write write : script) {
                if (write.delayMs > 0) {
                    Thread.sleep(write.delayMs);
                }
                out.write(write.bytes);
                out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            // each test fails on what its client receives
        }
    }

    private static String readRequestPath(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            if (head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                break;
            }
        }
        String requestLine = head.toString(StandardCharsets.US_ASCII).split("\r\n", 2)[0];
        return requestLine.split(" ")[1];
    }

    private static Write head(String contentType, String... extraHeaders) {
        StringBuilder head = new StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nTransfer-Encoding: chunked\r\n");
        for (String extraHeader : extraHeaders) {
            head.append(extraHeader).append("\r\n");
        }
        return new Write(0, head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static Write chunk(long delayMs, byte[] data) {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        chunk.writeBytes((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        chunk.writeBytes(data);
        chunk.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        return new Write(delayMs, chunk.toByteArray());
    }

    private static Write chunk(long delayMs, String data) {
        return chunk(delayMs, data.getBytes(StandardCharsets.UTF_8));
    }

    private static Write lastChunk(long delayMs, String trailers) {
        return new Write(delayMs, ("0\r\n" + trailers + "\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    /** A response read off the wire, its chunked framing decoded. */
    private static final class Received {
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, String> trailers = new LinkedHashMap<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int statusCode;

        String bodyAsUtf8() {
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    private static Received get(String path) throws IOException {
        try (Socket socket = new Socket("localhost", mockServer.getLocalPort())) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            Received received = new Received();
            String statusLine = readLine(in);
            received.statusCode = Integer.parseInt(statusLine.split(" ")[1]);
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                int colon = line.indexOf(':');
                received.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            if ("chunked".equalsIgnoreCase(received.headers.get("transfer-encoding"))) {
                while (true) {
                    int size = Integer.parseInt(readLine(in).split(";")[0].trim(), 16);
                    if (size == 0) {
                        while (!(line = readLine(in)).isEmpty()) {
                            int colon = line.indexOf(':');
                            received.trailers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                        }
                        return received;
                    }
                    received.body.writeBytes(in.readNBytes(size));
                    readLine(in);
                }
            }
            String contentLength = received.headers.get("content-length");
            if (contentLength != null) {
                received.body.writeBytes(in.readNBytes(Integer.parseInt(contentLength)));
            } else {
                received.body.writeBytes(in.readAllBytes());
            }
            return received;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        if (b == -1 && line.size() == 0) {
            throw new IOException("connection closed");
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    private static Expectation recordedExpectation(String path) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        Expectation[] recorded = mockServerClient.retrieveRecordedExpectations(request(path));
        while (recorded.length == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            recorded = mockServerClient.retrieveRecordedExpectations(request(path));
        }
        assertThat("the forward is recorded", recorded.length, is(1));
        return recorded[0];
    }

    private static void recordForward(String path, List<Write> script) {
        SCRIPTS.put(path, script);
        mockServerClient.when(request(path)).forward(forward().withHost("127.0.0.1").withPort(upstream.getLocalPort()));
    }

    /** Serves the recorded or converted response for {@code path}, without the upstream's Host the recording matches on. */
    private static void replay(String path, Expectation recordedOrConverted) {
        mockServerClient.reset();
        Expectation replay = new Expectation(request(path));
        if (recordedOrConverted.getHttpSseResponse() != null) {
            replay.thenRespondWithSse(recordedOrConverted.getHttpSseResponse());
        } else {
            replay.thenRespond(recordedOrConverted.getHttpResponse());
        }
        mockServerClient.upsert(replay);
    }

    private static long delayMillis(SseEvent event) {
        return event.getDelay() == null ? 0 : event.getDelay().getTimeUnit().toMillis(event.getDelay().getValue());
    }

    /** Re-reads expectations as a persisted recorded expectations file holds them. */
    private static Expectation[] jsonRoundTrip(Expectation... expectations) {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());
        return serializer.deserializeArray(serializer.serialize(expectations), true);
    }

    @Test(timeout = 60_000)
    public void shouldRelayRecordAndReplayNonAsciiSseSplitAcrossChunksWithNoCharset() throws Exception {
        String events = "data: café 🎉\n\ndata: 日本\n\n";
        byte[] bytes = events.getBytes(StandardCharsets.UTF_8);
        int splitInsideEAcute = "data: caf".length() + 1;
        int splitInsideEmoji = "data: café ".getBytes(StandardCharsets.UTF_8).length + 2;
        List<Write> script = List.of(
            head("text/event-stream"),
            chunk(0, java.util.Arrays.copyOfRange(bytes, 0, splitInsideEAcute)),
            chunk(50, java.util.Arrays.copyOfRange(bytes, splitInsideEAcute, splitInsideEmoji)),
            chunk(50, java.util.Arrays.copyOfRange(bytes, splitInsideEmoji, bytes.length)),
            lastChunk(0, ""));
        recordForward("/non-ascii", script);

        Received relayed = get("/non-ascii");
        assertThat(relayed.statusCode, is(200));
        assertThat("relayed byte for byte", relayed.body.toByteArray(), is(bytes));

        Expectation recorded = jsonRoundTrip(recordedExpectation("/non-ascii"))[0];
        assertThat("recorded as the text the client received", recorded.getHttpResponse().getBodyAsString(), is(events));

        // replayed as recorded, from a persisted recording
        replay("/non-ascii", recorded);
        assertThat(get("/non-ascii").body.toByteArray(), is(bytes));

        // replayed as an SSE fixture
        HttpSseResponse sse = new SseAwareExpectationConverter().convert(new Expectation[]{recorded})[0].getHttpSseResponse();
        assertThat(sse.getEvents().size(), is(2));
        assertThat(sse.getEvents().get(0).getData(), is("café 🎉"));
        assertThat(sse.getEvents().get(1).getData(), is("日本"));
        replay("/non-ascii", new Expectation(request()).thenRespondWithSse(sse));
        assertThat(get("/non-ascii").bodyAsUtf8(), is("data: café 🎉\n\ndata: 日本\n\n"));
    }




    @Test(timeout = 60_000)
    public void shouldRecordAndReplayCrlfSseWithCommentsIdsRetryAndMultiLineData() throws Exception {
        String events = ": keep-alive\r\n\r\nretry: 1500\r\nid: 7\r\nevent: delta\r\ndata: line one\r\ndata: line two\r\n\r\nid: 8\r\ndata: {\"k\":1}\r\n\r\n";
        recordForward("/crlf", List.of(
            head("text/event-stream; charset=utf-8"),
            chunk(0, ": keep-alive\r\n\r\n"),
            chunk(50, "retry: 1500\r\nid: 7\r\nevent: delta\r\ndata: line one\r\ndata: line two\r\n\r\n"),
            chunk(50, "id: 8\r\ndata: {\"k\":1}\r\n\r\n"),
            lastChunk(0, "")));

        assertThat("relayed byte for byte", get("/crlf").bodyAsUtf8(), is(events));

        Expectation recorded = jsonRoundTrip(recordedExpectation("/crlf"))[0];
        assertThat(recorded.getHttpResponse().getBodyAsString(), is(events));
        List<SseEvent> converted = new SseAwareExpectationConverter(50, true).convert(new Expectation[]{recorded})[0].getHttpSseResponse().getEvents();
        assertThat(converted.size(), is(3));
        assertThat(converted.get(0).getComment(), is(" keep-alive"));
        assertThat(converted.get(1).getRetry(), is(1500));
        assertThat(converted.get(1).getId(), is("7"));
        assertThat(converted.get(1).getEvent(), is("delta"));
        assertThat(converted.get(1).getData(), is("line one\nline two"));
        assertThat(converted.get(2).getId(), is("8"));
        assertThat(converted.get(2).getData(), is("{\"k\":1}"));
    }

    @Test(timeout = 60_000)
    public void shouldRelayRecordAndReplayAnEmptyStream() throws Exception {
        recordForward("/empty", List.of(
            head("text/event-stream"),
            lastChunk(0, "")));

        Received relayed = get("/empty");
        assertThat(relayed.statusCode, is(200));
        assertThat(relayed.body.size(), is(0));

        Expectation recorded = recordedExpectation("/empty");
        assertThat(recorded.getHttpResponse().getBodyAsString(), is(nullValue()));
        Expectation converted = new SseAwareExpectationConverter().convert(new Expectation[]{recorded})[0];
        assertThat(converted.getHttpResponse().getFirstHeader("x-mockserver-streamed"), is(""));
        replay("/empty", converted);
        Received replayed = get("/empty");
        assertThat(replayed.statusCode, is(200));
        assertThat(replayed.body.size(), is(0));
        assertThat(replayed.headers.containsKey("x-mockserver-response-time-ms"), is(false));
    }

    @Test(timeout = 60_000)
    public void shouldRelayRecordAndReplayABinaryEventStreamByteForByte() throws Exception {
        byte[] frame = new byte[256];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (byte) i;
        }
        recordForward("/binary", List.of(
            head("application/vnd.amazon.eventstream"),
            chunk(0, java.util.Arrays.copyOfRange(frame, 0, 100)),
            chunk(30, java.util.Arrays.copyOfRange(frame, 100, 256)),
            lastChunk(0, "")));

        assertThat(get("/binary").body.toByteArray(), is(frame));

        Expectation recorded = jsonRoundTrip(recordedExpectation("/binary"))[0];
        assertThat(recorded.getHttpResponse().getBodyAsRawBytes(), is(frame));
        replay("/binary", recorded);
        assertThat(get("/binary").body.toByteArray(), is(frame));
    }

    @Test(timeout = 60_000)
    public void shouldRelayAStreamLongerThanTheCaptureLimitWholeAndRecordItsStart() throws Exception {
        StringBuilder events = new StringBuilder();
        List<Write> script = new ArrayList<>();
        script.add(head("text/event-stream"));
        for (int i = 0; i < 400; i++) {
            String event = "data: event-" + i + "-" + "x".repeat(20) + "\n\n";
            events.append(event);
            script.add(chunk(0, event));
        }
        script.add(lastChunk(0, ""));
        recordForward("/long", script);

        String relayed = get("/long").bodyAsUtf8();
        assertThat(relayed.length(), is(events.length()));
        assertThat(relayed, is(events.toString()));

        HttpResponse recorded = recordedExpectation("/long").getHttpResponse();
        assertThat(recorded.getFirstHeader("x-mockserver-stream-truncated"), is("true"));
        assertThat(recorded.getBodyAsString(), is(events.substring(0, MAX_STREAMING_CAPTURE_BYTES)));
    }


}

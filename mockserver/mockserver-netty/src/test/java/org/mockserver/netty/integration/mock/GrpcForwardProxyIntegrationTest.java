package org.mockserver.netty.integration.mock;

import com.google.protobuf.Descriptors;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end gRPC forward-proxy + record/replay: a gRPC call to MockServer A (proxy) that matches a
 * {@code FORWARD} expectation is re-encoded to protobuf frames, relayed to MockServer B (the upstream
 * gRPC mock), and the framed response is decoded back to the caller. The forwarded exchange is
 * recorded on A with the decoded gRPC method JSON, so {@code retrieveRecordedExpectations} yields a
 * replayable gRPC mock.
 *
 * <p>The framed gRPC request/response are driven over HTTP/1.1 (the gRPC handlers live on the HTTP/1.1
 * pipeline too, since they are content-type driven), which keeps the test to a plain socket without a
 * bespoke HTTP/2 client — MockServer's normal forward client carries the framed body upstream.
 */
public class GrpcForwardProxyIntegrationTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";

    private static MockServer upstream;   // B: the real gRPC mock
    private static MockServer proxy;      // A: forwards to B
    private static MockServerClient upstreamClient;
    private static MockServerClient proxyClient;

    private static GrpcProtoDescriptorStore store;
    private static GrpcJsonMessageConverter converter;
    private static Descriptors.MethodDescriptor greetingMethod;

    @BeforeClass
    public static void startServers() throws Exception {
        store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get(DESCRIPTOR));
        converter = store.getConverter();
        greetingMethod = store.getMethod(SERVICE, "Greeting");

        byte[] descriptorBytes = Files.readAllBytes(Paths.get(DESCRIPTOR));

        upstream = new MockServer();
        upstreamClient = new MockServerClient("localhost", upstream.getLocalPort());
        upstreamClient.uploadGrpcDescriptor(descriptorBytes);

        proxy = new MockServer();
        proxyClient = new MockServerClient("localhost", proxy.getLocalPort());
        proxyClient.uploadGrpcDescriptor(descriptorBytes);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(upstreamClient);
        stopQuietly(proxyClient);
    }

    @Before
    public void resetExpectations() throws Exception {
        // keep descriptors, clear expectations + recorded log
        upstreamClient.reset();
        proxyClient.reset();
        byte[] descriptorBytes = Files.readAllBytes(Paths.get(DESCRIPTOR));
        upstreamClient.uploadGrpcDescriptor(descriptorBytes);
        proxyClient.uploadGrpcDescriptor(descriptorBytes);
    }

    @Test
    public void shouldForwardGrpcCallToUpstreamAndDecodeResponse() throws Exception {
        // upstream B mocks the unary Greeting RPC
        upstreamClient
            .when(request().withMethod("POST").withPath("/" + SERVICE + "/Greeting"))
            .respondWithGrpcStream(GrpcStreamResponse.grpcStreamResponse()
                .withStatusName("OK")
                .withMessage("{\"greeting\": \"Hello Tom\"}"));

        // proxy A forwards the Greeting RPC to B
        proxyClient
            .when(request().withMethod("POST").withPath("/" + SERVICE + "/Greeting"))
            .forward(forward().withHost("localhost").withPort(upstream.getLocalPort()));

        // caller sends a framed gRPC request to A
        byte[] requestFrame = GrpcFrameCodec.encode(converter.toProtobuf("{\"name\":\"Tom\"}", greetingMethod.getInputType()));
        HttpRawResponse response = sendGrpcCall(proxy.getLocalPort(), "/" + SERVICE + "/Greeting", requestFrame);

        // A returns the decoded-then-re-framed gRPC response to the caller
        assertThat(response.statusLine, containsString("200"));
        List<byte[]> messages = GrpcFrameCodec.decode(response.body);
        assertThat(messages, hasSize(1));
        assertThat(converter.toJson(messages.get(0), greetingMethod.getOutputType()), containsString("Hello Tom"));

        // the exchange was recorded on A with the decoded gRPC JSON, ready to replay as a mock
        Expectation[] recorded = proxyClient.retrieveRecordedExpectations(
            request().withPath("/" + SERVICE + "/Greeting"));
        assertThat("expected a recorded forwarded gRPC exchange", recorded.length, greaterThanOrEqualTo(1));
        String recordedResponseBody = recorded[0].getHttpResponse().getBodyAsString();
        assertThat(recordedResponseBody, containsString("Hello Tom"));
    }

    @Test
    public void shouldForwardUnmatchedGrpcCallInProxyModeAndRecordIt() throws Exception {
        // upstream B mocks the unary Greeting RPC
        upstreamClient
            .when(request().withMethod("POST").withPath("/" + SERVICE + "/Greeting"))
            .respondWithGrpcStream(GrpcStreamResponse.grpcStreamResponse()
                .withStatusName("OK")
                .withMessage("{\"greeting\": \"Hello Proxy\"}"));

        // A2 is a reverse proxy to B (no expectations) — exercises the unmatched-proxy forward path
        MockServer reverseProxy = new MockServer(upstream.getLocalPort(), "localhost");
        MockServerClient reverseProxyClient = new MockServerClient("localhost", reverseProxy.getLocalPort());
        try {
            reverseProxyClient.uploadGrpcDescriptor(Files.readAllBytes(Paths.get(DESCRIPTOR)));

            byte[] requestFrame = GrpcFrameCodec.encode(converter.toProtobuf("{\"name\":\"Ann\"}", greetingMethod.getInputType()));
            HttpRawResponse response = sendGrpcCall(reverseProxy.getLocalPort(), "/" + SERVICE + "/Greeting", requestFrame);

            assertThat(response.statusLine, containsString("200"));
            List<byte[]> messages = GrpcFrameCodec.decode(response.body);
            assertThat(messages, hasSize(1));
            assertThat(converter.toJson(messages.get(0), greetingMethod.getOutputType()), containsString("Hello Proxy"));

            Expectation[] recorded = reverseProxyClient.retrieveRecordedExpectations(
                request().withPath("/" + SERVICE + "/Greeting"));
            assertThat("expected a recorded forwarded gRPC exchange", recorded.length, greaterThanOrEqualTo(1));
            assertThat(recorded[0].getHttpResponse().getBodyAsString(), containsString("Hello Proxy"));
        } finally {
            stopQuietly(reverseProxyClient);
        }
    }

    @Test
    public void shouldPreserveNonOkStatusDeliveredInUpstreamTrailers() throws Exception {
        // A real gRPC server delivers the terminal grpc-status/grpc-message in HTTP TRAILERS (not as
        // plain headers). This upstream is a bare socket server that returns a chunked response ending
        // in trailer-form grpc-status: 5 (NOT_FOUND) with no message frame.
        try (RawGrpcUpstream rawUpstream = RawGrpcUpstream.startNotFound()) {

            proxyClient
                .when(request().withMethod("POST").withPath("/" + SERVICE + "/Greeting"))
                .forward(forward().withHost("127.0.0.1").withPort(rawUpstream.port()));

            byte[] requestFrame = GrpcFrameCodec.encode(converter.toProtobuf("{\"name\":\"Ghost\"}", greetingMethod.getInputType()));
            HttpRawResponse response = sendGrpcCall(proxy.getLocalPort(), "/" + SERVICE + "/Greeting", requestFrame);

            // the caller must receive the non-OK status, NOT a defaulted OK -- and per the gRPC wire
            // contract it must arrive as a TRAILER (the chunked trailer section on HTTP/1.1),
            // never as a plain response header
            assertThat(response.statusLine, containsString("200"));
            assertThat("grpc-status must be relayed as 5 (NOT_FOUND), not defaulted to 0",
                response.raw, containsString("grpc-status: 5"));
            // no "grpc-status:" header LINE in the head (the RFC 9110 "Trailer: grpc-status"
            // announcement header is expected there, and is not a grpc-status header itself)
            assertThat("grpc-status must be a trailer, not an initial response header",
                response.headerBlock, not(containsString("\r\ngrpc-status:")));
            assertThat("the head must announce the trailer per RFC 9110",
                response.headerBlock, containsString("trailer: grpc-status"));

            // and the recording must preserve the non-OK status so replay is faithful
            Expectation[] recorded = proxyClient.retrieveRecordedExpectations(
                request().withPath("/" + SERVICE + "/Greeting"));
            assertThat(recorded.length, greaterThanOrEqualTo(1));
            String recordedStatusName = recorded[0].getHttpResponse().getFirstHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER);
            String recordedStatus = recorded[0].getHttpResponse().getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER);
            assertThat("recorded exchange must preserve the upstream non-OK status",
                "NOT_FOUND".equals(recordedStatusName) || "5".equals(recordedStatus), is(true));
        }
    }

    /**
     * Issue #2988: an upstream answer with no message frame and {@code grpc-status: 0} must reach the
     * caller with no message frame (not an example message filled in for the missing body), and the
     * recording must hold {@code []} so it replays the same way.
     */
    @Test
    public void shouldRelayAndRecordOkResponseWithNoMessagesAsNoMessages() throws Exception {
        byte[] requestFrame = GrpcFrameCodec.encode(converter.toProtobuf("{\"name\":\"Nobody\"}", greetingMethod.getInputType()));
        Expectation[] recorded;
        try (RawGrpcUpstream rawUpstream = RawGrpcUpstream.startOkWithNoMessages()) {

            proxyClient
                .when(request().withMethod("POST").withPath("/" + SERVICE + "/Greeting"))
                .forward(forward().withHost("127.0.0.1").withPort(rawUpstream.port()));

            HttpRawResponse response = sendGrpcCall(proxy.getLocalPort(), "/" + SERVICE + "/Greeting", requestFrame);

            assertThat(response.statusLine, containsString("200"));
            assertThat("no message frame, not even a zero-length one", response.body.length, is(0));
            assertThat("grpc-status must be relayed unchanged", response.raw, containsString("grpc-status: 0"));

            recorded = proxyClient.retrieveRecordedExpectations(
                request().withPath("/" + SERVICE + "/Greeting"));
            assertThat(recorded.length, greaterThanOrEqualTo(1));
            assertThat(recorded[0].getHttpResponse().getBodyAsString(), is("[]"));
        }

        // replay the recording, with the upstream gone and only the recorded expectation left; the
        // recorded Host is the forward target's, so it is dropped as it would be to replay elsewhere
        for (Expectation expectation : recorded) {
            ((org.mockserver.model.HttpRequest) expectation.getHttpRequest()).removeHeader("Host");
        }
        proxyClient.reset();
        proxyClient.uploadGrpcDescriptor(Files.readAllBytes(Paths.get(DESCRIPTOR)));
        proxyClient.upsert(recorded);

        HttpRawResponse replayed = sendGrpcCall(proxy.getLocalPort(), "/" + SERVICE + "/Greeting", requestFrame);

        assertThat(replayed.statusLine, containsString("200"));
        assertThat("the replay must not add a message either", replayed.body.length, is(0));
        assertThat(replayed.raw, containsString("grpc-status: 0"));
    }

    /**
     * A minimal socket server that plays the part of a real gRPC upstream by returning grpc-status in
     * HTTP/1.1 chunked TRAILERS (as grpc-java does), which MockServer-as-upstream never does.
     */
    private static final class RawGrpcUpstream implements AutoCloseable {
        private final java.net.ServerSocket serverSocket;
        private final Thread thread;

        private RawGrpcUpstream(java.net.ServerSocket serverSocket, Thread thread) {
            this.serverSocket = serverSocket;
            this.thread = thread;
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        static RawGrpcUpstream startNotFound() throws Exception {
            return start("grpc-status: 5\r\n" + "grpc-message: greeting not found\r\n");
        }

        static RawGrpcUpstream startOkWithNoMessages() throws Exception {
            return start("grpc-status: 0\r\n");
        }

        private static RawGrpcUpstream start(String trailers) throws Exception {
            java.net.ServerSocket ss = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try (Socket client = ss.accept()) {
                        client.setSoTimeout(5000);
                        drainRequest(client.getInputStream());
                        // zero-length body (no message frame) + trailer-form grpc-status
                        String resp = "HTTP/1.1 200 OK\r\n" +
                            "content-type: application/grpc\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Trailer: grpc-status, grpc-message\r\n" +
                            "Connection: close\r\n" +
                            "\r\n" +
                            "0\r\n" +
                            trailers +
                            "\r\n";
                        OutputStream out = client.getOutputStream();
                        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                    } catch (Exception ignored) {
                        // socket closed / accept interrupted on shutdown
                    }
                }
            }, "raw-grpc-upstream");
            t.setDaemon(true);
            t.start();
            return new RawGrpcUpstream(ss, t);
        }

        private static void drainRequest(InputStream in) throws Exception {
            // read the request headers, then the declared Content-Length body, so the client's write
            // completes before we respond and close
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int prev3 = -1, prev2 = -1, prev1 = -1, b;
            while ((b = in.read()) != -1) {
                header.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                    break;
                }
                prev3 = prev2;
                prev2 = prev1;
                prev1 = b;
            }
            String headerText = header.toString("ISO-8859-1");
            int contentLength = 0;
            for (String line : headerText.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            for (int i = 0; i < contentLength; i++) {
                if (in.read() == -1) {
                    break;
                }
            }
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            thread.interrupt();
        }
    }

    // --- raw HTTP/1.1 gRPC client (framed binary body) ---

    private static final class HttpRawResponse {
        final String statusLine;
        final byte[] body;
        final String headerBlock;
        /** the entire response as received, lower-cased -- includes the chunked trailer section */
        final String raw;

        HttpRawResponse(String statusLine, byte[] body, String headerBlock, String raw) {
            this.statusLine = statusLine;
            this.body = body;
            this.headerBlock = headerBlock;
            this.raw = raw;
        }
    }

    private HttpRawResponse sendGrpcCall(int port, String path, byte[] frame) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            String headers = "POST " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Connection: close\r\n" +
                "Content-Type: " + GrpcStatusMapper.GRPC_CONTENT_TYPE + "\r\n" +
                "te: trailers\r\n" +
                "Content-Length: " + frame.length + "\r\n" +
                "\r\n";
            output.write(headers.getBytes(StandardCharsets.UTF_8));
            output.write(frame);
            output.flush();

            byte[] raw = readAll(socket.getInputStream());
            int split = indexOfCrlfCrlf(raw);
            String headerBlock = new String(raw, 0, split < 0 ? raw.length : split, StandardCharsets.ISO_8859_1);
            String statusLine = headerBlock.split("\r\n", 2)[0];
            byte[] body = split < 0 ? new byte[0] : extractBody(raw, split + 4, headerBlock);
            return new HttpRawResponse(statusLine, body, headerBlock.toLowerCase(),
                new String(raw, StandardCharsets.ISO_8859_1).toLowerCase());
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static int indexOfCrlfCrlf(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Extract the message body, honouring HTTP/1.1 chunked transfer-encoding when present (the gRPC
     * response may be chunked) so the returned bytes are the raw gRPC frame(s).
     */
    private static byte[] extractBody(byte[] raw, int bodyStart, String headerBlock) throws Exception {
        boolean chunked = headerBlock.toLowerCase().contains("transfer-encoding: chunked");
        byte[] body = new byte[raw.length - bodyStart];
        System.arraycopy(raw, bodyStart, body, 0, body.length);
        if (!chunked) {
            return body;
        }
        // de-chunk: <hex-size>\r\n<data>\r\n ... 0\r\n
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < body.length) {
            int eol = -1;
            for (int i = pos; i + 1 < body.length; i++) {
                if (body[i] == '\r' && body[i + 1] == '\n') {
                    eol = i;
                    break;
                }
            }
            if (eol < 0) {
                break;
            }
            String sizeHex = new String(body, pos, eol - pos, StandardCharsets.ISO_8859_1).trim();
            if (sizeHex.isEmpty()) {
                break;
            }
            int size = Integer.parseInt(sizeHex.split(";")[0], 16);
            pos = eol + 2;
            if (size == 0) {
                break;
            }
            out.write(body, pos, Math.min(size, body.length - pos));
            pos += size + 2; // skip data + trailing CRLF
        }
        return out.toByteArray();
    }
}

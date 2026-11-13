package org.mockserver.netty.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end gRPC-Web test driven over a <strong>real HTTP/1.1 socket</strong>, exactly as a
 * browser gRPC-Web client (grpc-web / improbable-eng) would speak to MockServer.
 * <p>
 * Every other gRPC-Web test in this repository ({@code GrpcWebHandlerTest},
 * {@code GrpcWebTranslatorTest}) asserts at handler / translator level using an
 * {@code EmbeddedChannel}, so none of them exercises the actual pipeline wiring that
 * {@code PortUnificationHandler} installs for HTTP/1.1, nor the bytes a real client would receive
 * off the wire. A browser gRPC-Web client cannot read HTTP trailers at all — it depends entirely
 * on the in-body trailer frame (flag {@code 0x80}) carrying {@code grpc-status}. This test posts a
 * real {@code application/grpc-web} (and {@code application/grpc-web-text}) framed request to a
 * running server and asserts on the raw response bytes:
 * <ul>
 *   <li>the response {@code content-type} is the negotiated gRPC-Web subtype;</li>
 *   <li>{@code grpc-status} is NOT an HTTP header/trailer — it must be inside the body;</li>
 *   <li>the body is a length-prefixed message frame (flag {@code 0x00}) followed by a trailer frame
 *       (flag {@code 0x80}) whose ASCII body carries {@code grpc-status: 0};</li>
 *   <li>the message frame decodes to the mocked protobuf response.</li>
 * </ul>
 * <p>
 * The {@code -text} variant additionally asserts the whole body is base64-encoded, which is what a
 * browser XHR fallback transport requires.
 * <p>
 * Uses {@code DynamicMessage} plus the loaded descriptor set rather than protoc-generated stubs, so
 * the test needs no code generation step.
 */
public class GrpcWebOverTheWireIntegrationTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String METHOD = "Greeting";
    private static final String PATH = "/" + SERVICE + "/" + METHOD;
    private static final String LIST_PATH = "/" + SERVICE + "/ListGreetings";
    private static final String GREETING_DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";

    /** The gRPC-Web trailer frame flag byte. */
    private static final byte TRAILER_FLAG = (byte) 0x80;

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    private final Map<String, Descriptors.ServiceDescriptor> services = new LinkedHashMap<>();
    private Descriptors.Descriptor requestType;
    private Descriptors.Descriptor responseType;

    @Before
    public void setUp() throws Exception {
        byte[] greetingDescriptorBytes = Files.readAllBytes(Paths.get(GREETING_DESCRIPTOR));
        registerServices(greetingDescriptorBytes);

        Descriptors.MethodDescriptor greeting = method(SERVICE, METHOD);
        requestType = greeting.getInputType();
        responseType = greeting.getOutputType();

        mockServer = new MockServer();
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        // uploading a descriptor is what makes PortUnificationHandler install the gRPC handlers
        // on the HTTP/1.1 pipeline (grpcDescriptorStore.hasServices())
        mockServerClient.uploadGrpcDescriptor(greetingDescriptorBytes);

        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(PATH)
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status", "0")
                    .withBody("{\"greeting\":\"Hello World\"}")
            );
    }

    @After
    public void tearDown() {
        stopQuietly(mockServerClient);
        mockServerClient = null;
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
    }

    /**
     * A binary {@code application/grpc-web} request must come back framed the way a browser client
     * expects: message frame + trailer frame carrying {@code grpc-status: 0} in the body.
     */
    @Test
    public void shouldServeGrpcWebBinaryOverTheWire() throws Exception {
        byte[] requestFrame = GrpcFrameCodec.encode(helloRequest("World").toByteArray());

        RawHttpResponse response = postOverSocket("application/grpc-web", requestFrame);

        assertThat("must be HTTP 200", response.statusCode, is(200));
        assertThat("content-type must be a gRPC-Web subtype",
            response.header("content-type"), containsString("application/grpc-web"));
        assertThat("the -text variant must NOT be selected for a binary request",
            response.header("content-type"), not(containsString("grpc-web-text")));
        // a browser client cannot read HTTP trailers/headers for grpc-status: it MUST be in the body
        assertThat("grpc-status must not leak into HTTP headers/trailers",
            response.header("grpc-status"), is(nullOrEmpty()));

        assertGrpcWebFraming(response.body);
    }

    /**
     * The {@code application/grpc-web-text} variant: the request body is base64-encoded, and the
     * whole response body must come back base64-encoded, wrapping the same message + trailer frames.
     */
    @Test
    public void shouldServeGrpcWebTextOverTheWire() throws Exception {
        byte[] requestFrame = GrpcFrameCodec.encode(helloRequest("World").toByteArray());
        byte[] base64RequestBody = Base64.getEncoder().encode(requestFrame);

        RawHttpResponse response = postOverSocket("application/grpc-web-text", base64RequestBody);

        assertThat("must be HTTP 200", response.statusCode, is(200));
        assertThat("content-type must be the gRPC-Web-text subtype",
            response.header("content-type"), containsString("application/grpc-web-text"));
        assertThat("grpc-status must not leak into HTTP headers/trailers",
            response.header("grpc-status"), is(nullOrEmpty()));

        // whole body is base64-encoded for the -text variant
        byte[] decoded = Base64.getDecoder().decode(response.body);
        assertGrpcWebFraming(decoded);
    }

    @Test
    public void shouldServeEveryMessageOfAServerStreamOverGrpcWeb() throws Exception {
        mockServerClient
            .when(request().withMethod("POST").withPath(LIST_PATH))
            .respond(response().withBody("[{\"greeting\":\"a\"},{\"greeting\":\"b\"},{\"greeting\":\"c\"}]"));

        RawHttpResponse response = postOverSocket(LIST_PATH, "application/grpc-web", GrpcFrameCodec.encode(helloRequest("World").toByteArray()));

        assertThat(response.statusCode, is(200));
        assertThat(response.header("content-type"), is("application/grpc-web"));
        assertThat(messagesAndTrailer(response.body), is(Arrays.asList("a", "b", "c", "grpc-status: 0\r\n")));
    }

    @Test
    public void shouldServeAServerStreamOfNoMessagesOverGrpcWeb() throws Exception {
        mockServerClient
            .when(request().withMethod("POST").withPath(LIST_PATH))
            .respond(response().withBody("[]"));

        RawHttpResponse response = postOverSocket(LIST_PATH, "application/grpc-web", GrpcFrameCodec.encode(helloRequest("World").toByteArray()));

        assertThat(response.statusCode, is(200));
        assertThat(messagesAndTrailer(response.body), is(Arrays.asList("grpc-status: 0\r\n")));
    }

    @Test
    public void shouldServeAnErrorStatusInTheTrailerFrameOverGrpcWeb() throws Exception {
        mockServerClient
            .when(request().withMethod("POST").withPath(LIST_PATH))
            .respond(response()
                .withTrailer("grpc-status", "5")
                .withTrailer("grpc-message", "no greetings")
                .withTrailer("x-reason", "missing"));

        RawHttpResponse response = postOverSocket(LIST_PATH, "application/grpc-web", GrpcFrameCodec.encode(helloRequest("World").toByteArray()));

        assertThat(response.statusCode, is(200));
        assertThat(response.header("grpc-status"), is(nullOrEmpty()));
        assertThat(messagesAndTrailer(response.body),
            is(Arrays.asList("grpc-status: 5\r\ngrpc-message: no greetings\r\nx-reason: missing\r\n")));
    }

    @Test
    public void shouldServeAGrpcStreamResponseOverGrpcWeb() throws Exception {
        mockServerClient
            .when(request().withMethod("POST").withPath(LIST_PATH))
            .respondWithGrpcStream(org.mockserver.model.GrpcStreamResponse.grpcStreamResponse()
                .withMessage("{\"greeting\":\"a\"}")
                .withMessage("{\"greeting\":\"b\"}")
                .withStatusName("ABORTED")
                .withStatusMessage("stopped"));

        RawHttpResponse response = postOverSocket(LIST_PATH, "application/grpc-web", GrpcFrameCodec.encode(helloRequest("World").toByteArray()));

        assertThat(response.statusCode, is(200));
        assertThat(response.header("content-type"), is("application/grpc-web"));
        assertThat(messagesAndTrailer(response.body), is(Arrays.asList("a", "b", "grpc-status: 10\r\ngrpc-message: stopped\r\n")));
    }

    @Test
    public void shouldServeAGrpcStreamResponseOverGrpcWebText() throws Exception {
        mockServerClient
            .when(request().withMethod("POST").withPath(LIST_PATH))
            .respondWithGrpcStream(org.mockserver.model.GrpcStreamResponse.grpcStreamResponse()
                .withMessage("{\"greeting\":\"a\"}")
                .withMessage("{\"greeting\":\"bc\"}"));

        byte[] requestBody = Base64.getEncoder().encode(GrpcFrameCodec.encode(helloRequest("World").toByteArray()));
        RawHttpResponse response = postOverSocket(LIST_PATH, "application/grpc-web-text", requestBody);

        assertThat(response.statusCode, is(200));
        assertThat(response.header("content-type"), is("application/grpc-web-text"));
        assertThat(messagesAndTrailer(decodeBase64Segments(response.body)), is(Arrays.asList("a", "bc", "grpc-status: 0\r\n")));
    }

    /**
     * Decodes a gRPC-Web text body, in which each frame may be base64-encoded on its own, so padding
     * can appear in the middle.
     */
    private static byte[] decodeBase64Segments(byte[] body) {
        String text = new String(body, StandardCharsets.US_ASCII);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int start = 0;
        for (int i = 0; i + 4 <= text.length(); i += 4) {
            if (text.charAt(i + 3) == '=' || i + 4 == text.length()) {
                decoded.writeBytes(Base64.getDecoder().decode(text.substring(start, i + 4)));
                start = i + 4;
            }
        }
        assertThat("base64 text must be a whole number of 4-character groups", start, is(text.length()));
        return decoded.toByteArray();
    }

    @Test
    public void shouldEndAGrpcStreamResponseOverGrpcWebWithDeadlineExceededInTheTrailerFrame() throws Exception {
        mockServerClient
            .when(request().withMethod("POST").withPath(LIST_PATH))
            .respondWithGrpcStream(org.mockserver.model.GrpcStreamResponse.grpcStreamResponse()
                .withMessage("{\"greeting\":\"a\"}")
                .withMessage("{\"greeting\":\"late\"}", org.mockserver.model.Delay.seconds(10)));

        RawHttpResponse response = postOverSocket(LIST_PATH, "application/grpc-web",
            GrpcFrameCodec.encode(helloRequest("World").toByteArray()), "grpc-timeout: 500m\r\n");

        assertThat(response.statusCode, is(200));
        assertThat(messagesAndTrailer(response.body),
            is(Arrays.asList("a", "grpc-status: 4\r\ngrpc-message: deadline exceeded after 500ms\r\n")));
    }

    /**
     * Each message frame's greeting, then the text of the trailer frame, which must come last.
     */
    private List<String> messagesAndTrailer(byte[] body) throws Exception {
        List<String> contents = new ArrayList<>();
        List<Frame> frames = parseFrames(body);
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (frame.flag == TRAILER_FLAG) {
                assertThat("the trailer frame must be the last frame", i, is(frames.size() - 1));
                contents.add(new String(frame.payload, StandardCharsets.US_ASCII));
            } else {
                assertThat(frame.flag, is((byte) 0));
                DynamicMessage reply = DynamicMessage.parseFrom(responseType, frame.payload);
                contents.add((String) reply.getField(responseType.findFieldByName("greeting")));
            }
        }
        return contents;
    }

    /**
     * Asserts the canonical gRPC-Web response framing on the given (already base64-decoded, if
     * applicable) body bytes: a message frame followed by a trailer frame carrying
     * {@code grpc-status: 0}, and that the message frame decodes to the mocked protobuf response.
     */
    private void assertGrpcWebFraming(byte[] body) throws Exception {
        assertThat("body must be present", body, is(notNullValue()));
        List<Frame> frames = parseFrames(body);
        assertThat("expected a message frame + a trailer frame", frames.size(), is(2));

        Frame messageFrame = frames.get(0);
        assertThat("first frame must be a data (message) frame, not a trailer frame",
            messageFrame.flag & 0x80, is(0));
        // decode the protobuf message frame — proves a real browser client could deserialize it
        DynamicMessage reply = DynamicMessage.parseFrom(responseType, messageFrame.payload);
        assertThat((String) reply.getField(responseType.findFieldByName("greeting")), is("Hello World"));

        Frame trailerFrame = frames.get(1);
        assertThat("second frame must be the trailer frame with flag 0x80",
            trailerFrame.flag, is(TRAILER_FLAG));
        String trailerText = new String(trailerFrame.payload, StandardCharsets.US_ASCII);
        assertThat("trailer frame must carry grpc-status in its body",
            trailerText, containsString("grpc-status: 0\r\n"));
    }

    // ---- raw HTTP/1.1 socket client ----

    private static final class RawHttpResponse {
        final int statusCode;
        final Map<String, String> headers;
        final byte[] body;

        RawHttpResponse(int statusCode, Map<String, String> headers, byte[] body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private static final class Frame {
        final byte flag;
        final byte[] payload;

        Frame(byte flag, byte[] payload) {
            this.flag = flag;
            this.payload = payload;
        }
    }

    /**
     * Posts the given body to {@link #PATH} over a raw HTTP/1.1 socket and reads the full response,
     * so the assertions run against the exact bytes a real gRPC-Web client would receive.
     */
    private RawHttpResponse postOverSocket(String contentType, byte[] body) throws Exception {
        return postOverSocket(PATH, contentType, body);
    }

    private RawHttpResponse postOverSocket(String path, String contentType, byte[] body) throws Exception {
        return postOverSocket(path, contentType, body, "");
    }

    private RawHttpResponse postOverSocket(String path, String contentType, byte[] body, String extraHeaders) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", mockServer.getLocalPort()), 10_000);
            socket.setSoTimeout(30_000);

            StringBuilder head = new StringBuilder();
            head.append("POST ").append(path).append(" HTTP/1.1\r\n");
            head.append("Host: localhost:").append(mockServer.getLocalPort()).append("\r\n");
            head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append(extraHeaders);
            head.append("Connection: close\r\n");
            head.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();

            return readResponse(socket.getInputStream());
        }
    }

    private RawHttpResponse readResponse(InputStream in) throws Exception {
        // read until the server closes (Connection: close) or ends a chunked body with no HTTP
        // trailers, as a streamed gRPC-Web response does without closing the connection
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            if (endsWithLastChunk(buffer.toByteArray())) {
                break;
            }
        }
        byte[] raw = buffer.toByteArray();

        int headerEnd = indexOfCrLfCrLf(raw);
        if (headerEnd < 0) {
            throw new AssertionError("no header/body separator in response of " + raw.length + " bytes");
        }
        String headerText = new String(raw, 0, headerEnd, StandardCharsets.US_ASCII);
        String[] lines = headerText.split("\r\n");

        String statusLine = lines[0];
        String[] statusParts = statusLine.split(" ", 3);
        int statusCode = Integer.parseInt(statusParts[1]);

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = lines[i].substring(colon + 1).trim();
                headers.put(name, value);
            }
        }

        int bodyStart = headerEnd + 4;
        byte[] body;
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            body = decodeChunked(raw, bodyStart);
        } else {
            body = new byte[raw.length - bodyStart];
            System.arraycopy(raw, bodyStart, body, 0, body.length);
        }
        return new RawHttpResponse(statusCode, headers, body);
    }

    private static boolean endsWithLastChunk(byte[] raw) {
        int headerEnd = indexOfCrLfCrLf(raw);
        if (headerEnd < 0 || !new String(raw, 0, headerEnd, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) {
            return false;
        }
        byte[] last = "\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        if (raw.length - headerEnd < last.length) {
            return false;
        }
        for (int i = 0; i < last.length; i++) {
            if (raw[raw.length - last.length + i] != last[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] decodeChunked(byte[] raw, int offset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = offset;
        while (pos < raw.length) {
            int lineEnd = indexOfCrLf(raw, pos);
            if (lineEnd < 0) {
                break;
            }
            String sizeLine = new String(raw, pos, lineEnd - pos, StandardCharsets.US_ASCII).trim();
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) {
                sizeLine = sizeLine.substring(0, semi);
            }
            int chunkSize = Integer.parseInt(sizeLine.trim(), 16);
            pos = lineEnd + 2;
            if (chunkSize == 0) {
                break;
            }
            out.write(raw, pos, chunkSize);
            pos += chunkSize + 2; // skip chunk data + trailing CRLF
        }
        return out.toByteArray();
    }

    private static int indexOfCrLf(byte[] data, int from) {
        for (int i = from; i + 1 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfCrLfCrLf(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Parses length-prefixed gRPC / gRPC-Web frames: 1 flag byte + 4-byte big-endian length +
     * payload.
     */
    private static List<Frame> parseFrames(byte[] body) {
        List<Frame> frames = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(body);
        while (buf.remaining() >= 5) {
            byte flag = buf.get();
            int len = buf.getInt();
            if (len < 0 || len > buf.remaining()) {
                throw new AssertionError("frame length " + len + " exceeds remaining " + buf.remaining());
            }
            byte[] payload = new byte[len];
            buf.get(payload);
            frames.add(new Frame(flag, payload));
        }
        return frames;
    }

    // ---- descriptor / protobuf helpers ----

    private DynamicMessage helloRequest(String name) {
        return DynamicMessage.newBuilder(requestType)
            .setField(requestType.findFieldByName("name"), name)
            .build();
    }

    private Descriptors.MethodDescriptor method(String serviceName, String methodName) {
        Descriptors.ServiceDescriptor serviceDescriptor = services.get(serviceName);
        if (serviceDescriptor == null) {
            throw new IllegalStateException("service not found in descriptors: " + serviceName);
        }
        Descriptors.MethodDescriptor methodDescriptor = serviceDescriptor.findMethodByName(methodName);
        if (methodDescriptor == null) {
            throw new IllegalStateException("method not found: " + serviceName + "/" + methodName);
        }
        return methodDescriptor;
    }

    private void registerServices(byte[] descriptorBytes) throws Exception {
        DescriptorProtos.FileDescriptorSet fileDescriptorSet =
            DescriptorProtos.FileDescriptorSet.parseFrom(descriptorBytes);
        for (DescriptorProtos.FileDescriptorProto fileDescriptorProto : fileDescriptorSet.getFileList()) {
            Descriptors.FileDescriptor fileDescriptor = Descriptors.FileDescriptor.buildFrom(
                fileDescriptorProto, new Descriptors.FileDescriptor[0]);
            for (Descriptors.ServiceDescriptor serviceDescriptor : fileDescriptor.getServices()) {
                services.put(serviceDescriptor.getFullName(), serviceDescriptor);
            }
        }
    }

    private static org.hamcrest.Matcher<String> nullOrEmpty() {
        return org.hamcrest.Matchers.anyOf(
            org.hamcrest.Matchers.nullValue(String.class),
            is(""));
    }
}

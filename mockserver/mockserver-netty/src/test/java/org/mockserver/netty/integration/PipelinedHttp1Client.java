package org.mockserver.netty.integration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A raw HTTP/1.1 client for pipelining: it writes several requests in a single {@code write()}, then reads the
 * responses back one at a time, framed by {@code Content-Length}, chunked encoding, or the end of the connection.
 * <p>
 * Every read goes through one buffered stream, so the client can keep reading the same connection after a
 * {@code CONNECT} tunnel opens or a WebSocket upgrade completes.
 */
public final class PipelinedHttp1Client implements AutoCloseable {

    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 10_000;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private PipelinedHttp1Client(Socket socket) throws IOException {
        this.socket = socket;
        this.socket.setSoTimeout(DEFAULT_READ_TIMEOUT_MILLIS);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    public static PipelinedHttp1Client plain(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setTcpNoDelay(true);
        return new PipelinedHttp1Client(socket);
    }

    public static PipelinedHttp1Client tls(int port, SSLContext sslContext) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        plain.setTcpNoDelay(true);
        SSLSocket tls = (SSLSocket) sslContext.getSocketFactory().createSocket(plain, "localhost", port, true);
        // a raw SSLSocket skips hostname verification unless asked, so check the certificate names localhost
        SSLParameters sslParameters = tls.getSSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(sslParameters);
        tls.startHandshake();
        return new PipelinedHttp1Client(tls);
    }

    public PipelinedHttp1Client withReadTimeout(int millis) throws SocketException {
        socket.setSoTimeout(millis);
        return this;
    }

    public static String get(String path) {
        return rawRequest("GET", path);
    }

    public static String head(String path) {
        return rawRequest("HEAD", path);
    }

    /**
     * A request with no body, and any extra header lines given as {@code "Name: value"}.
     */
    public static String rawRequest(String method, String path, String... headerLines) {
        return rawRequestWithBody(method, path, null, headerLines);
    }

    /**
     * A request with a {@code Content-Length} body, or no body when {@code body} is null.
     */
    public static String rawRequestWithBody(String method, String path, String body, String... headerLines) {
        StringBuilder request = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: localhost\r\n");
        for (String headerLine : headerLines) {
            request.append(headerLine).append("\r\n");
        }
        if (body != null) {
            request.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        }
        request.append("\r\n");
        if (body != null) {
            request.append(body);
        }
        return request.toString();
    }

    /**
     * Sends every request in one {@code write()}, so the server sees them pipelined in as few reads as it takes.
     */
    public void pipeline(String... requests) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        for (String request : requests) {
            wire.writeBytes(request.getBytes(StandardCharsets.UTF_8));
        }
        write(wire.toByteArray());
    }

    public void write(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    /**
     * Reads one response to a request that was not {@code HEAD}.
     */
    public Response readResponse() throws IOException {
        return readResponse(false);
    }

    /**
     * Reads one response. A response to {@code HEAD}, a 1xx, a 204 and a 304 have no body whatever their headers
     * say; any other response without {@code Content-Length} or chunked encoding is ended by the connection closing.
     */
    public Response readResponse(boolean toHead) throws IOException {
        String head = readHead();
        String[] lines = head.split("\r\n");
        String[] statusLine = lines[0].split(" ", 3);
        if (statusLine.length < 2 || !statusLine[0].startsWith("HTTP/")) {
            throw new IOException("not a response status line: \"" + lines[0] + "\"");
        }
        int status = Integer.parseInt(statusLine[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        byte[] body;
        if (toHead || status < 200 || status == 204 || status == 304) {
            body = new byte[0];
        } else if (headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked")) {
            body = readChunkedBody();
        } else if (headers.containsKey("content-length")) {
            body = readExactly(Integer.parseInt(headers.get("content-length")));
        } else {
            body = in.readAllBytes();
        }
        return new Response(status, head, headers, body);
    }

    /**
     * Reads one response per entry of {@code toHead}, each framed as a response to {@code HEAD} or not.
     */
    public List<Response> readResponses(boolean... toHead) throws IOException {
        List<Response> responses = new ArrayList<>();
        for (boolean head : toHead) {
            responses.add(readResponse(head));
        }
        return responses;
    }

    public String readHead() throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int lastFour = 0;
        while (lastFour != 0x0D0A0D0A) {
            int next = in.read();
            if (next == -1) {
                throw new EOFException("connection closed before a response head, received: " + head.toString(StandardCharsets.ISO_8859_1));
            }
            head.write(next);
            lastFour = (lastFour << 8) | next;
        }
        String text = head.toString(StandardCharsets.ISO_8859_1);
        return text.substring(0, text.length() - 4);
    }

    public byte[] readExactly(int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length < length) {
            throw new EOFException("connection closed after " + bytes.length + " of " + length + " bytes");
        }
        return bytes;
    }

    private byte[] readChunkedBody() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine();
            int size;
            try {
                size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
            } catch (NumberFormatException notAChunkSize) {
                throw new IOException("expected a chunk size, read \"" + sizeLine + "\" after " + body.size() + " body bytes: " + body.toString(StandardCharsets.ISO_8859_1));
            }
            if (size == 0) {
                while (!readLine().isEmpty()) {
                    // trailers
                }
                return body.toByteArray();
            }
            body.writeBytes(readExactly(size));
            String delimiter = readLine();
            if (!delimiter.isEmpty()) {
                throw new IOException("expected CRLF after a chunk, read \"" + delimiter + "\"");
            }
        }
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = in.read()) != -1) {
            if (character == '\n' && line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
                return line.substring(0, line.length() - 1);
            }
            line.append((char) character);
        }
        throw new EOFException("connection closed mid-line, received: " + line);
    }

    /**
     * Reads every byte until the server closes the connection, or until {@code millis} pass with nothing to read.
     */
    public String readUntilClosedOrQuiet(int millis) throws IOException {
        int previous = socket.getSoTimeout();
        socket.setSoTimeout(millis);
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                read.write(buffer, 0, count);
            }
        } catch (SocketTimeoutException quiet) {
            // nothing more arrived
        } catch (SocketException reset) {
            // closed abruptly
        } finally {
            if (!socket.isClosed()) {
                socket.setSoTimeout(previous);
            }
        }
        return read.toString(StandardCharsets.ISO_8859_1);
    }

    /**
     * @return true if the server closed the connection, false if it is still open after {@code millis}
     */
    public boolean isClosedByServer(int millis) throws IOException {
        socket.setSoTimeout(millis);
        try {
            return in.read() == -1;
        } catch (SocketTimeoutException open) {
            return false;
        } catch (SocketException reset) {
            return true;
        }
    }

    public Socket socket() {
        return socket;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public static final class Response {
        public final int status;
        public final String head;
        public final Map<String, String> headers;
        public final byte[] body;

        Response(int status, String head, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.head = head;
            this.headers = headers;
            this.body = body;
        }

        public String body() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String toString() {
            return status + " " + body();
        }
    }
}

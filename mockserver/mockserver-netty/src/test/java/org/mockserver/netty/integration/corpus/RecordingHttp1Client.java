package org.mockserver.netty.integration.corpus;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A raw HTTP/1.1 connection that keeps every byte the server sends, split into the responses, WebSocket frames and
 * close it reads. Responses are framed by {@code Content-Length}, chunked encoding (whose chunk-size lines and
 * trailers are kept as they arrived) or the end of the connection.
 */
final class RecordingHttp1Client implements AutoCloseable {

    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final CorpusNormaliser normaliser;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final StringBuilder transcript = new StringBuilder();

    private RecordingHttp1Client(Socket socket, CorpusNormaliser normaliser) throws IOException {
        this.socket = socket;
        this.socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
        this.normaliser = normaliser;
    }

    static RecordingHttp1Client connect(int port, CorpusNormaliser normaliser) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setTcpNoDelay(true);
        normaliser.knownPort(socket.getLocalPort(), "client");
        return new RecordingHttp1Client(socket, normaliser);
    }

    /**
     * Sends the requests in a single {@code write()}.
     */
    void send(String... requests) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        for (String request : requests) {
            wire.writeBytes(request.getBytes(StandardCharsets.UTF_8));
        }
        out.write(wire.toByteArray());
        out.flush();
    }

    void readResponse() throws IOException {
        readResponse(false);
    }

    /**
     * Reads one response. A response to {@code HEAD}, a 1xx, a 204 and a 304 have no body; any other response without
     * {@code Content-Length} or chunked encoding ends when the connection does.
     */
    void readResponse(boolean toHead) throws IOException {
        String head = readHead();
        transcript.append("<<< response head\n").append(CorpusNormaliser.escape(normaliser.normaliseHead(head).getBytes(StandardCharsets.ISO_8859_1)));
        String[] lines = head.split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ", 3)[1]);
        String contentLength = null;
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = lines[i].substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    contentLength = value;
                } else if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                    chunked = true;
                }
            }
        }
        if (toHead || status < 200 || status == 204 || status == 304) {
            return;
        }
        if (chunked) {
            readChunkedBody();
        } else if (contentLength != null) {
            readExactly(Integer.parseInt(contentLength));
            flush("response body, content-length " + contentLength);
        } else {
            readUntilClosed();
        }
    }

    /**
     * Reads one unmasked WebSocket frame a server sends.
     */
    void readWebSocketFrame() throws IOException {
        byte[] header = readExactly(2);
        int length = header[1] & 0x7F;
        if (length == 126) {
            byte[] extended = readExactly(2);
            length = ((extended[0] & 0xFF) << 8) | (extended[1] & 0xFF);
        } else if (length == 127) {
            throw new IOException("a WebSocket frame too long for the corpus");
        }
        readExactly(length);
        flush("websocket frame");
    }

    /**
     * Reads everything until the server closes the connection, and records the close.
     */
    void readUntilClosed() throws IOException {
        try {
            int next;
            while ((next = in.read()) != -1) {
                captured.write(next);
            }
        } catch (SocketException reset) {
            // a reset is recorded as a close: whether the peer's close is a FIN or a RST depends on what it had unread
        }
        if (captured.size() > 0) {
            flush("bytes before close");
        }
        transcript.append("<<< connection closed by server\n");
    }

    int localPort() {
        return socket.getLocalPort();
    }

    String transcript() {
        return transcript.toString();
    }

    private String readHead() throws IOException {
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
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private void readChunkedBody() throws IOException {
        while (true) {
            String sizeLine = readLine();
            int size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
            if (size == 0) {
                while (!readLine().isEmpty()) {
                    // trailers, kept in the capture
                }
                flush("last chunk and trailers");
                return;
            }
            readExactly(size);
            if (!readLine().isEmpty()) {
                throw new IOException("expected CRLF after a chunk");
            }
            flush("chunk");
        }
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = in.read()) != -1) {
            captured.write(character);
            if (character == '\n' && line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
                return line.substring(0, line.length() - 1);
            }
            line.append((char) character);
        }
        throw new EOFException("connection closed mid-line, received: " + line);
    }

    private byte[] readExactly(int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        captured.writeBytes(bytes);
        if (bytes.length < length) {
            throw new EOFException("connection closed after " + bytes.length + " of " + length + " bytes");
        }
        return bytes;
    }

    private void flush(String segment) {
        transcript.append("<<< ").append(segment).append('\n').append(CorpusNormaliser.escape(captured.toByteArray()));
        captured.reset();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}

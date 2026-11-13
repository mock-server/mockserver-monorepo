package org.mockserver.netty.integration.proxy.websocket;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A WebSocket peer on a plain blocking socket that writes and reads frames byte for byte, so a test controls (and
 * sees) fragmentation, opcodes, close payloads and frame sizes exactly. Used as the client (masked frames) and, via
 * {@link RawWebSocketUpstream}, as the upstream (unmasked frames).
 */
public final class RawWebSocket implements AutoCloseable {

    public static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    public static final String CLIENT_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    public static final int TEXT = 0x1;
    public static final int BINARY = 0x2;
    public static final int CONTINUATION = 0x0;
    public static final int CLOSE = 0x8;
    public static final int PING = 0x9;
    public static final int PONG = 0xA;

    private final Socket socket;
    private final boolean masked;

    public RawWebSocket(Socket socket, boolean masked) {
        this.socket = socket;
        this.masked = masked;
    }

    public Socket socket() {
        return socket;
    }

    // ---- connecting ------------------------------------------------------------------------------------------------

    public static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
        socket.setSoTimeout(15_000);
        return socket;
    }

    /**
     * Opens an HTTP CONNECT tunnel through the proxy on {@code socket}; returns the proxy's status code.
     */
    public static int connectTunnel(Socket socket, String targetAuthority, String proxyAuthorization) throws IOException {
        write(socket, "CONNECT " + targetAuthority + " HTTP/1.1\r\nHost: " + targetAuthority + "\r\n"
            + (proxyAuthorization != null ? "Proxy-Authorization: " + proxyAuthorization + "\r\n" : "") + "\r\n");
        Response response = readResponseHead(socket.getInputStream());
        if (response.status != 200) {
            // a refusal carries a body: read it so the connection's state is not misread
            String length = response.header("content-length");
            if (length != null) {
                readFully(socket.getInputStream(), Integer.parseInt(length.trim()));
            }
        }
        return response.status;
    }

    /**
     * Opens a SOCKS5 tunnel to 127.0.0.1:{@code targetPort}, with username/password authentication when
     * {@code username} is not null; returns true when the proxy accepted it.
     */
    public static boolean socks5(Socket socket, int targetPort, String username, String password) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(username == null ? new byte[]{5, 1, 0} : new byte[]{5, 1, 2});
        out.flush();
        byte[] method = readFully(in, 2);
        if (username != null) {
            if (method[1] != 2) {
                return false;
            }
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(1);
            auth.write(username.length());
            auth.writeBytes(username.getBytes(StandardCharsets.US_ASCII));
            auth.write(password.length());
            auth.writeBytes(password.getBytes(StandardCharsets.US_ASCII));
            out.write(auth.toByteArray());
            out.flush();
            if (readFully(in, 2)[1] != 0) {
                return false;
            }
        } else if (method[1] != 0) {
            return false;
        }
        out.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (targetPort >> 8), (byte) targetPort});
        out.flush();
        byte[] reply = readFully(in, 4);
        if (reply[1] != 0) {
            return false;
        }
        int addressLength = reply[3] == 1 ? 4 : reply[3] == 4 ? 16 : readFully(in, 1)[0] & 0xff;
        readFully(in, addressLength + 2);
        return true;
    }

    /**
     * Starts TLS over {@code socket}, trusting any certificate (the tests check relaying, not trust).
     */
    public static Socket tls(Socket socket, String host) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
        SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(socket, host, socket.getPort(), true);
        sslSocket.setSoTimeout(15_000);
        sslSocket.startHandshake();
        return sslSocket;
    }

    /**
     * Sends a WebSocket upgrade for {@code path} with {@code hostHeader} and the extra header lines given ("Name: value"),
     * and reads the response head (and, for a refusal with a Content-Length, its body). A 101 is checked for a correct
     * {@code Sec-WebSocket-Accept}.
     */
    public static Response upgrade(Socket socket, String hostHeader, String path, String... extraHeaderLines) throws Exception {
        StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + CLIENT_KEY + "\r\nSec-WebSocket-Version: 13\r\n");
        for (String line : extraHeaderLines) {
            request.append(line).append("\r\n");
        }
        request.append("\r\n");
        write(socket, request.toString());
        Response response = readResponseHead(socket.getInputStream());
        if (response.status == 101) {
            String accept = response.header("sec-websocket-accept");
            if (!acceptFor(CLIENT_KEY).equals(accept)) {
                throw new AssertionError("Sec-WebSocket-Accept " + accept + " is not the one for the client's key: " + response.head);
            }
        } else {
            String length = response.header("content-length");
            if (length != null) {
                response.body = new String(readFully(socket.getInputStream(), Integer.parseInt(length.trim())), StandardCharsets.UTF_8);
            }
        }
        return response;
    }

    public static String acceptFor(String key) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII)));
    }

    // ---- frames ----------------------------------------------------------------------------------------------------

    public void send(boolean fin, int opcode, byte[] payload) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(encode(fin, opcode, payload, masked));
        out.flush();
    }

    public void sendText(String text) throws IOException {
        send(true, TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendClose(int code, String reason) throws IOException {
        send(true, CLOSE, closePayload(code, reason));
    }

    public static byte[] closePayload(int code, String reason) {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        return payload;
    }

    /**
     * @return the next frame, or null when the connection ends (or is reset) before one starts
     */
    public Frame read() throws IOException {
        InputStream in = socket.getInputStream();
        int first;
        try {
            first = in.read();
        } catch (java.net.SocketException reset) {
            return null;
        }
        if (first == -1) {
            return null;
        }
        int second = readByte(in);
        long length = second & 0x7f;
        if (length == 126) {
            length = new DataInputStream(in).readUnsignedShort();
        } else if (length == 127) {
            length = new DataInputStream(in).readLong();
        }
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IOException("frame length " + length + " is outside 0.." + Integer.MAX_VALUE);
        }
        byte[] mask = readFully(in, (second & 0x80) != 0 ? 4 : 0);
        byte[] payload = readFully(in, (int) length);
        for (int i = 0; i < payload.length && mask.length == 4; i++) {
            payload[i] ^= mask[i % 4];
        }
        return new Frame((first & 0x80) != 0, (first >> 4) & 0x7, first & 0x0f, payload, mask.length == 4);
    }

    public static byte[] encode(boolean fin, int opcode, byte[] payload, boolean masked) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write((fin ? 0x80 : 0) | opcode);
        int maskBit = masked ? 0x80 : 0;
        if (payload.length < 126) {
            frame.write(maskBit | payload.length);
        } else if (payload.length <= 0xffff) {
            frame.write(maskBit | 126);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xff);
        } else {
            frame.write(maskBit | 127);
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((int) (length >> shift) & 0xff);
            }
        }
        byte[] mask = {0x11, 0x22, 0x33, 0x44};
        if (masked) {
            frame.write(mask, 0, mask.length);
        }
        byte[] body = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            body[i] = (byte) (masked ? payload[i] ^ mask[i % 4] : payload[i]);
        }
        frame.writeBytes(body);
        return frame.toByteArray();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    // ---- shared stream helpers -------------------------------------------------------------------------------------

    public static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next == -1) {
                throw new IOException("connection closed after: " + head.toString(StandardCharsets.ISO_8859_1));
            }
            head.write(next);
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    public static Response readResponseHead(InputStream in) throws IOException {
        String head = readHead(in);
        return new Response(Integer.parseInt(head.substring(9, 12)), head, parseHeaders(head));
    }

    /**
     * Header names lower-cased, each value line kept separately and in order.
     */
    static Map<String, List<String>> parseHeaders(String head) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String[] lines = head.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.computeIfAbsent(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), name -> new ArrayList<>())
                    .add(lines[i].substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new IOException("connection ended part-way through a frame header");
        }
        return b;
    }

    public static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        for (int read = 0; read < length; ) {
            int count = in.read(bytes, read, length - read);
            if (count == -1) {
                throw new IOException("connection ended part-way through (" + read + " of " + length + " bytes)");
            }
            read += count;
        }
        return bytes;
    }

    public static final class Response {
        public final int status;
        public final String head;
        public final Map<String, List<String>> headers;
        public String body = "";

        Response(int status, String head, Map<String, List<String>> headers) {
            this.status = status;
            this.head = head;
            this.headers = headers;
        }

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        @Override
        public String toString() {
            return head + body;
        }
    }

    public record Frame(boolean fin, int rsv, int opcode, byte[] payload, boolean masked) {

        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }

        public int closeCode() {
            return payload.length >= 2 ? ((payload[0] & 0xff) << 8) | (payload[1] & 0xff) : -1;
        }

        public String closeReason() {
            return payload.length > 2 ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
        }

        /** A short, exact description used in assertions, e.g. {@code TEXT fin "hello"} or {@code CLOSE fin 4001 "bye"}. */
        public String describe() {
            String kind = switch (opcode) {
                case TEXT -> "TEXT";
                case BINARY -> "BINARY";
                case CONTINUATION -> "CONTINUATION";
                case CLOSE -> "CLOSE";
                case PING -> "PING";
                case PONG -> "PONG";
                default -> "OPCODE_" + opcode;
            };
            String content = opcode == CLOSE
                ? closeCode() + " \"" + closeReason() + "\""
                : payload.length > 64 ? payload.length + " bytes" : "\"" + text() + "\"";
            return kind + (fin ? " fin " : " more ") + (rsv != 0 ? "rsv" + rsv + " " : "") + content;
        }

        @Override
        public String toString() {
            return describe();
        }
    }
}

package org.mockserver.netty.integration.proxy.socks;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * With proxy authentication configured, SOCKS5 relays only for a client that authenticates with username/password and
 * SOCKS4 is refused; without it, both relay as before.
 */
public class SocksProxyAuthenticationIntegrationTest {

    private static final String USERNAME = "proxy-user";
    private static final String PASSWORD = "proxy-password";
    private static final int READ_TIMEOUT_MILLIS = 5_000;

    private static ClientAndServer upstream;
    private static ClientAndServer authenticatingProxy;
    private static ClientAndServer openProxy;

    @BeforeClass
    public static void startServers() {
        upstream = startClientAndServer(configuration());
        authenticatingProxy = startClientAndServer(configuration()
            .proxyAuthenticationUsername(USERNAME)
            .proxyAuthenticationPassword(PASSWORD));
        openProxy = startClientAndServer(configuration());
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(openProxy);
        stopQuietly(authenticatingProxy);
        stopQuietly(upstream);
    }

    @Before
    public void reset() {
        upstream.reset();
        upstream.when(request().withPath("/relayed/.*")).respond(response().withStatusCode(200).withBody("from upstream"));
    }

    @Test
    public void shouldRefuseASocks5ClientOfferingOnlyNoAuth() throws Exception {
        try (Socket socket = connectTo(authenticatingProxy)) {
            // a greeting must arrive on its own to be recognised as SOCKS5
            socket.getOutputStream().write(new byte[]{5, 1, 0});
            socket.getOutputStream().flush();
            assertThat("NO ACCEPTABLE METHODS", readBytes(socket.getInputStream(), 2), is(new byte[]{5, (byte) 0xff}));

            // a client that carries on regardless
            ByteArrayOutputStream sent = new ByteArrayOutputStream();
            sent.write(socks5Connect());
            sent.write(get("/relayed/no-auth"));
            writeUnlessClosed(socket, sent.toByteArray());

            assertThat("then closed", readUntilClosed(socket).length, is(0));
        }
        upstream.verify(request().withPath("/relayed/.*"), exactly(0));
    }

    @Test
    public void shouldRelayForASocks5ClientOfferingNoAuthAndPasswordWithTheRightCredentials() throws Exception {
        try (Socket socket = connectTo(authenticatingProxy)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[]{5, 2, 0, 2});
            out.flush();
            assertThat("USERNAME/PASSWORD chosen", readBytes(in, 2), is(new byte[]{5, 2}));

            out.write(passwordAuth(USERNAME, PASSWORD));
            out.flush();
            assertThat("authenticated", readBytes(in, 2), is(new byte[]{1, 0}));

            out.write(socks5Connect());
            out.flush();
            readSocks5ConnectReply(in);

            out.write(get("/relayed/password"));
            out.flush();
            assertThat(readHead(socket), startsWith("HTTP/1.1 200"));
        }
        upstream.verify(request().withPath("/relayed/password"), exactly(1));
    }

    @Test
    public void shouldRefuseASocks5ClientWithTheWrongCredentials() throws Exception {
        try (Socket socket = connectTo(authenticatingProxy)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[]{5, 2, 0, 2});
            out.flush();
            assertThat(readBytes(in, 2), is(new byte[]{5, 2}));

            ByteArrayOutputStream sent = new ByteArrayOutputStream();
            sent.write(passwordAuth(USERNAME, "wrong-password"));
            sent.write(socks5Connect());
            sent.write(get("/relayed/wrong"));
            out.write(sent.toByteArray());
            out.flush();

            byte[] received = readUntilClosed(socket);
            assertThat("auth reply", received.length, is(2));
            assertThat("RFC 1929 version", received[0], is((byte) 1));
            assertThat("RFC 1929 failure status", received[1], not(is((byte) 0)));
        }
        upstream.verify(request().withPath("/relayed/.*"), exactly(0));
    }

    @Test
    public void shouldRefuseSocks4WhenCredentialsAreConfigured() throws Exception {
        try (Socket socket = connectTo(authenticatingProxy)) {
            // a request must arrive on its own to be recognised as SOCKS4
            socket.getOutputStream().write(socks4Connect());
            socket.getOutputStream().flush();
            byte[] reply = readBytes(socket.getInputStream(), 8);
            assertThat("request rejected or failed", reply[1], is((byte) 0x5b));

            // a client that carries on regardless
            writeUnlessClosed(socket, get("/relayed/socks4"));

            byte[] received = readUntilClosed(socket);
            assertThat("then closed", new String(received, StandardCharsets.ISO_8859_1), is(""));
        }
        upstream.verify(request().withPath("/relayed/.*"), exactly(0));
    }

    @Test
    public void shouldRelaySocks5WithoutCredentialsWhenNoneAreConfigured() throws Exception {
        try (Socket socket = connectTo(openProxy)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[]{5, 1, 0});
            out.flush();
            assertThat("NO_AUTH chosen", readBytes(in, 2), is(new byte[]{5, 0}));

            out.write(socks5Connect());
            out.flush();
            readSocks5ConnectReply(in);

            out.write(get("/relayed/open-socks5"));
            out.flush();
            assertThat(readHead(socket), startsWith("HTTP/1.1 200"));
        }
        upstream.verify(request().withPath("/relayed/open-socks5"), exactly(1));
    }

    @Test
    public void shouldRelaySocks4WhenNoCredentialsAreConfigured() throws Exception {
        try (Socket socket = connectTo(openProxy)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(socks4Connect());
            out.flush();
            byte[] reply = readBytes(in, 8);
            assertThat("request granted", reply[1], is((byte) 0x5a));

            out.write(get("/relayed/open-socks4"));
            out.flush();
            assertThat(readHead(socket), startsWith("HTTP/1.1 200"));
        }
        upstream.verify(request().withPath("/relayed/open-socks4"), exactly(1));
    }

    private static byte[] socks5Connect() {
        int port = upstream.getLocalPort();
        return new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port};
    }

    private static byte[] socks4Connect() {
        int port = upstream.getLocalPort();
        return new byte[]{4, 1, (byte) (port >> 8), (byte) port, 127, 0, 0, 1, 0};
    }

    private static byte[] passwordAuth(String username, String password) {
        byte[] user = username.getBytes(StandardCharsets.US_ASCII);
        byte[] pass = password.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream auth = new ByteArrayOutputStream();
        auth.write(1);
        auth.write(user.length);
        auth.write(user, 0, user.length);
        auth.write(pass.length);
        auth.write(pass, 0, pass.length);
        return auth.toByteArray();
    }

    private static byte[] get(String path) {
        return ("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + upstream.getLocalPort() + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static void readSocks5ConnectReply(InputStream in) throws IOException {
        byte[] reply = readBytes(in, 4);
        assertThat("SOCKS5 CONNECT succeeded", reply[1], is((byte) 0));
        // the bound address, whose length depends on its type, then the bound port
        int addressLength = reply[3] == 1 ? 4 : reply[3] == 4 ? 16 : readBytes(in, 1)[0] & 0xff;
        readBytes(in, addressLength + 2);
    }

    private static void writeUnlessClosed(Socket socket, byte[] bytes) {
        try {
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        } catch (IOException closed) {
            // closed by MockServer
        }
    }

    private static Socket connectTo(ClientAndServer proxy) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", proxy.getLocalPort()), READ_TIMEOUT_MILLIS);
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        return socket;
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        for (int read = 0; read < length; ) {
            int n = in.read(bytes, read, length - read);
            if (n == -1) {
                throw new IOException("closed after " + read + " of " + length + " bytes");
            }
            read += n;
        }
        return bytes;
    }

    /** The status line and headers of the next response, with its body read past. */
    private static String readHead(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b == -1) {
                break;
            }
            head.write(b);
        }
        String text = head.toString(StandardCharsets.ISO_8859_1);
        int contentLength = 0;
        for (String line : text.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        readBytes(in, contentLength);
        return text;
    }

    /** All that arrives until the connection closes; fails if it stays open for the whole read timeout. */
    private static byte[] readUntilClosed(Socket socket) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        try {
            for (int n; (n = socket.getInputStream().read(buffer)) != -1; ) {
                received.write(buffer, 0, n);
            }
        } catch (SocketTimeoutException timeout) {
            throw new AssertionError("connection still open after receiving " + received.size() + " bytes: " + new String(received.toByteArray(), StandardCharsets.ISO_8859_1));
        } catch (SocketException reset) {
            // closed with unread bytes: a reset
        }
        return received.toByteArray();
    }
}

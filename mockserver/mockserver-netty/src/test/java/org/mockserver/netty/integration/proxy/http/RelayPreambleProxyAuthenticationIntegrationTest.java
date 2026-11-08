package org.mockserver.netty.integration.proxy.http;

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
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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
 * With proxy authentication configured, a client cannot open a relay by sending the loopback preamble of MockServer's
 * own CONNECT/SOCKS relay itself, while tunnels opened with credentials still relay.
 */
public class RelayPreambleProxyAuthenticationIntegrationTest {

    private static final String USERNAME = "proxy-user";
    private static final String PASSWORD = "proxy-password";
    private static final int READ_TIMEOUT_MILLIS = 5_000;
    private static final int QUIET_MILLIS = 1_000;

    private static ClientAndServer upstream;
    private static ClientAndServer proxy;

    @BeforeClass
    public static void startServers() {
        upstream = startClientAndServer(configuration());
        proxy = startClientAndServer(configuration()
            .proxyAuthenticationUsername(USERNAME)
            .proxyAuthenticationPassword(PASSWORD));
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(proxy);
        stopQuietly(upstream);
    }

    @Before
    public void reset() {
        upstream.reset();
        upstream.when(request().withPath("/relayed/.*")).respond(response().withStatusCode(200).withBody("from upstream"));
    }

    @Test
    public void shouldNotRelayForAClientThatSendsTheRelayLoopbackPreambleItself() throws Exception {
        try (Socket socket = connectToProxy()) {
            write(socket, "PROXIED_127.0.0.1:" + upstream.getLocalPort());
            // as the relay does, the request follows only once the preamble has had its answer
            socket.setSoTimeout(QUIET_MILLIS);
            String received = readUntilClosedOrQuiet(socket);
            if (!socket.isClosed() && !socket.isInputShutdown()) {
                try {
                    write(socket, "GET /relayed/preamble HTTP/1.1\r\nHost: 127.0.0.1:" + upstream.getLocalPort() + "\r\nContent-Length: 0\r\n\r\n");
                    received += readUntilClosedOrQuiet(socket);
                } catch (SocketException closed) {
                    // closed by MockServer
                }
            }

            assertThat("the preamble is not acknowledged", received, not(startsWith("PROXIED_RESPONSE_")));
            assertThat("nothing from upstream", received, not(containsString("from upstream")));
        }
        upstream.verify(request().withPath("/relayed/preamble"), exactly(0));
    }

    @Test
    public void shouldRequireCredentialsForAConnectTunnel() throws Exception {
        try (Socket socket = connectToProxy()) {
            write(socket, "CONNECT 127.0.0.1:" + upstream.getLocalPort() + " HTTP/1.1\r\nHost: 127.0.0.1:" + upstream.getLocalPort() + "\r\n\r\n");

            assertThat(readHead(socket), startsWith("HTTP/1.1 407"));
        }
        upstream.verify(request().withPath("/relayed/.*"), exactly(0));
    }

    @Test
    public void shouldRelayThroughAConnectTunnelOpenedWithCredentials() throws Exception {
        try (Socket socket = connectToProxy()) {
            String basic = Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
            write(socket, "CONNECT 127.0.0.1:" + upstream.getLocalPort() + " HTTP/1.1\r\nHost: 127.0.0.1:" + upstream.getLocalPort() + "\r\nProxy-Authorization: Basic " + basic + "\r\n\r\n");
            assertThat(readHead(socket), startsWith("HTTP/1.1 200"));

            write(socket, "GET /relayed/connect HTTP/1.1\r\nHost: 127.0.0.1:" + upstream.getLocalPort() + "\r\nContent-Length: 0\r\n\r\n");

            assertThat(readHead(socket), startsWith("HTTP/1.1 200"));
        }
        upstream.verify(request().withPath("/relayed/connect"), exactly(1));
    }

    @Test
    public void shouldRelayThroughASocks5TunnelOpenedWithCredentials() throws Exception {
        try (Socket socket = connectToProxy()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            // greeting offering only username/password
            out.write(new byte[]{5, 1, 2});
            out.flush();
            assertThat(readBytes(in, 2), is(new byte[]{5, 2}));

            byte[] user = USERNAME.getBytes(StandardCharsets.US_ASCII);
            byte[] pass = PASSWORD.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(1);
            auth.write(user.length);
            auth.write(user);
            auth.write(pass.length);
            auth.write(pass);
            out.write(auth.toByteArray());
            out.flush();
            assertThat(readBytes(in, 2), is(new byte[]{1, 0}));

            int port = upstream.getLocalPort();
            out.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
            out.flush();
            byte[] reply = readBytes(in, 4);
            assertThat("SOCKS5 CONNECT succeeded", reply[1], is((byte) 0));
            // the bound address, whose length depends on its type, then the bound port
            int addressLength = reply[3] == 1 ? 4 : reply[3] == 4 ? 16 : readBytes(in, 1)[0] & 0xff;
            readBytes(in, addressLength + 2);

            write(socket, "GET /relayed/socks HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nContent-Length: 0\r\n\r\n");

            assertThat(readHead(socket), startsWith("HTTP/1.1 200"));
        }
        upstream.verify(request().withPath("/relayed/socks"), exactly(1));
    }

    private static Socket connectToProxy() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", proxy.getLocalPort()), READ_TIMEOUT_MILLIS);
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        return socket;
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
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

    /** The status line and headers of the next response. */
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

    /** All that arrives until the connection closes, or until nothing more arrives within the read timeout. */
    private static String readUntilClosedOrQuiet(Socket socket) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        try {
            for (int n; (n = socket.getInputStream().read(buffer)) != -1; ) {
                received.write(buffer, 0, n);
            }
        } catch (SocketTimeoutException | SocketException ignored) {
            // quiet, or reset: either way nothing more is coming
        }
        return received.toString(StandardCharsets.ISO_8859_1);
    }
}

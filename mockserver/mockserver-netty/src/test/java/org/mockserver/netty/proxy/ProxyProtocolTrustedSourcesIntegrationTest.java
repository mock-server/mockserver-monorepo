package org.mockserver.netty.proxy;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * {@code proxyProtocolTrustedSources} over real loopback sockets: a PROXY protocol header is applied only on a
 * connection from a listed source; one from any other source is closed without an answer and nothing is forwarded,
 * while the same source can still send requests without a PROXY header and open CONNECT and SOCKS tunnels, whose
 * loopback leg back into MockServer starts with a preamble that shares the PROXY v1 signature's first bytes.
 */
public class ProxyProtocolTrustedSourcesIntegrationTest {

    private static final byte[] V2_SIGNATURE = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};

    private static EchoServer echoServer;
    private static MockServer trustingLoopback;
    private static MockServer trustingOthers;
    private static MockServerClient trustingOthersClient;

    @BeforeClass
    public static void startServers() {
        echoServer = new EchoServer(false);
        trustingLoopback = new MockServer(configuration().transparentProxyEnabled(true).proxyProtocolTrustedSources("127.0.0.1, ::1"), 0);
        trustingOthers = new MockServer(configuration().transparentProxyEnabled(true).proxyProtocolTrustedSources("10.0.0.0/8, fd00::/8"), 0);
        trustingOthersClient = new MockServerClient("127.0.0.1", trustingOthers.getLocalPort());
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(trustingOthersClient);
        stopQuietly(trustingLoopback);
        stopQuietly(trustingOthers);
        stopQuietly(echoServer);
    }

    @Test
    public void shouldApplyAProxyProtocolHeaderFromATrustedSource() throws Exception {
        String marker = "trusted-" + System.nanoTime();

        String received = exchange(trustingLoopback, ascii(v1HeaderTo(echoServer.getPort()) + get("/trusted", marker)));

        assertThat(received, startsWith("HTTP/1.1 200"));
        assertThat("forwarded to the destination the header named", received, containsString(marker));
    }

    @Test
    public void shouldCloseAConnectionThatSendsAProxyProtocolV1HeaderFromAnUntrustedSource() throws Exception {
        String marker = "untrusted-v1-" + System.nanoTime();

        String received = exchange(trustingOthers, ascii(v1HeaderTo(echoServer.getPort()) + get("/untrusted-v1", marker)));

        assertThat("closed without an answer", received, is(""));
        assertThat(trustingOthersClient.retrieveRecordedRequests(request().withPath("/untrusted-v1")), arrayWithSize(0));
    }

    @Test
    public void shouldCloseAConnectionThatSendsAProxyProtocolV2HeaderFromAnUntrustedSource() throws Exception {
        String marker = "untrusted-v2-" + System.nanoTime();
        int port = echoServer.getPort();
        // PROXY command over TCP/IPv4: source 127.0.0.1:12345, destination 127.0.0.1:<echo server>
        byte[] header = concat(V2_SIGNATURE, new byte[]{0x21, 0x11, 0x00, 0x0C, 127, 0, 0, 1, 127, 0, 0, 1, 0x30, 0x39, (byte) (port >> 8), (byte) port});

        String received = exchange(trustingOthers, concat(header, ascii(get("/untrusted-v2", marker))));

        assertThat("closed without an answer", received, is(""));
        assertThat(trustingOthersClient.retrieveRecordedRequests(request().withPath("/untrusted-v2")), arrayWithSize(0));
    }

    @Test
    public void shouldStillServeAnUntrustedSourceThatSendsNoProxyProtocolHeader() throws Exception {
        trustingOthersClient.when(request().withPath("/plain")).respond(response().withBody("plain"));

        String received = exchange(trustingOthers, ascii(get("/plain", "no-header")));

        assertThat(received, startsWith("HTTP/1.1 200"));
        assertThat(received, containsString("plain"));
    }

    @Test
    public void shouldStillOpenAConnectTunnelForAnUntrustedSource() throws Exception {
        trustingOthersClient.when(request().withPath("/through-connect")).respond(response().withBody("through connect"));
        int port = echoServer.getPort();

        try (Socket socket = new Socket("127.0.0.1", trustingOthers.getLocalPort())) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(ascii("CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n"));
            assertThat(readHead(socket.getInputStream()), startsWith("HTTP/1.1 200"));

            assertThat(answerThrough(socket, "/through-connect"), containsString("through connect"));
        }
    }

    @Test
    public void shouldStillOpenASocks5TunnelForAnUntrustedSource() throws Exception {
        trustingOthersClient.when(request().withPath("/through-socks")).respond(response().withBody("through socks"));
        int port = echoServer.getPort();

        try (Socket socket = new Socket("127.0.0.1", trustingOthers.getLocalPort())) {
            socket.setSoTimeout(30_000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            socket.getOutputStream().write(new byte[]{5, 1, 0});
            byte[] method = new byte[2];
            input.readFully(method);
            assertThat("no authentication", method[1], is((byte) 0));
            socket.getOutputStream().write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
            byte[] reply = new byte[10];
            input.readFully(reply);
            assertThat("connect succeeded", reply[1], is((byte) 0));

            assertThat(answerThrough(socket, "/through-socks"), containsString("through socks"));
        }
    }

    private static String answerThrough(Socket tunnel, String path) throws IOException {
        tunnel.getOutputStream().write(ascii(get(path, "tunnelled")));
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        InputStream input = tunnel.getInputStream();
        byte[] buffer = new byte[8192];
        for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
            received.write(buffer, 0, read);
        }
        return received.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static String readHead(InputStream input) throws IOException {
        StringBuilder head = new StringBuilder();
        for (int read = input.read(); read != -1; read = input.read()) {
            head.append((char) read);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) {
                break;
            }
        }
        return head.toString();
    }

    private static String v1HeaderTo(int port) {
        return "PROXY TCP4 127.0.0.1 127.0.0.1 12345 " + port + "\r\n";
    }

    private static String get(String path, String marker) {
        return "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Marker: " + marker + "\r\nConnection: close\r\n\r\n";
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    /**
     * What the server sent before it closed the connection; a reset counts as a close, as the server may close with
     * bytes of the request still unread.
     */
    private static String exchange(MockServer server, byte[] sent) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
            socket.setSoTimeout(30_000);
            OutputStream output = socket.getOutputStream();
            output.write(sent);
            output.flush();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[8192];
            try {
                for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                    received.write(buffer, 0, read);
                }
            } catch (SocketException reset) {
                // closed by the server
            }
            return received.toString(StandardCharsets.ISO_8859_1.name());
        }
    }
}

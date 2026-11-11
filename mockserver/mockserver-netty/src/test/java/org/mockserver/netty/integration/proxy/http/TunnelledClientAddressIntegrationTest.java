package org.mockserver.netty.integration.proxy.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Format;
import org.mockserver.model.HttpRequest;
import org.mockserver.socket.tls.KeyStoreFactory;

import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.Retries.tryWaitForSuccess;

/**
 * A request sent through a CONNECT or SOCKS tunnel, which MockServer relays back into itself over a loopback
 * connection, is recorded with the tunnel client's address, not the loopback's; and that address is MockServer's own
 * knowledge of the client, which nothing the client sends through the tunnel can change.
 */
public class TunnelledClientAddressIntegrationTest {

    private static final int READ_TIMEOUT_MILLIS = 5_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static ClientAndServer upstream;
    private static ClientAndServer proxy;
    private static ClientAndServer trustingProxy;
    private static final List<SocketAddress> binaryClientAddresses = new CopyOnWriteArrayList<>();

    @BeforeClass
    public static void startServers() {
        upstream = startClientAndServer(configuration());
        proxy = startClientAndServer(configuration()
            .binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) -> binaryClientAddresses.add(clientAddress)));
        trustingProxy = startClientAndServer(configuration().transparentProxyEnabled(true).proxyProtocolTrustedSources("127.0.0.1"));
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(trustingProxy);
        stopQuietly(proxy);
        stopQuietly(upstream);
    }

    @Before
    public void reset() {
        proxy.reset();
        trustingProxy.reset();
        binaryClientAddresses.clear();
        upstream.reset();
        upstream.when(request().withPath("/tunnelled/.*")).respond(response().withStatusCode(200).withBody("from upstream"));
    }

    @Test
    public void shouldRecordTheClientAddressOfARequestThroughAConnectTunnel() throws Exception {
        String clientAddress;
        try (Socket socket = connectTo(proxy)) {
            clientAddress = addressOf(socket);
            openConnectTunnel(socket, upstream.getLocalPort());

            write(socket, get("/tunnelled/connect"));

            assertThat(readHead(socket), startsWith("HTTP/1.1 200"));
        }
        assertThat(recordedRemoteAddress(proxy, "/tunnelled/connect"), is(clientAddress));
    }

    @Test
    public void shouldRecordTheClientAddressOfAnHttpsRequestThroughAConnectTunnel() throws Exception {
        String clientAddress;
        try (Socket socket = connectTo(proxy)) {
            clientAddress = addressOf(socket);
            openConnectTunnel(socket, upstream.getLocalPort());
            SSLSocket tls = (SSLSocket) new KeyStoreFactory(configuration(), new MockServerLogger()).sslContext().getSocketFactory()
                .createSocket(socket, "127.0.0.1", upstream.getLocalPort(), true);
            tls.setUseClientMode(true);
            tls.startHandshake();

            write(tls, get("/tunnelled/https"));

            assertThat(readHead(tls), startsWith("HTTP/1.1 200"));
        }
        assertThat(recordedRemoteAddress(proxy, "/tunnelled/https"), is(clientAddress));
    }

    @Test
    public void shouldGiveTheBinaryProxyListenerTheClientAddressOfABinaryMessageThroughAConnectTunnel() throws Exception {
        byte[] message = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        try (ServerSocket echo = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); Socket socket = connectTo(proxy)) {
            Thread echoing = new Thread(() -> echoOneConnection(echo), "tunnelled-binary-echo");
            echoing.setDaemon(true);
            echoing.start();
            openConnectTunnel(socket, echo.getLocalPort());

            socket.getOutputStream().write(message);
            socket.getOutputStream().flush();

            assertThat(readBytes(socket.getInputStream(), message.length), is(message));
            SocketAddress clientAddress = socket.getLocalSocketAddress();
            tryWaitForSuccess(() -> assertThat(binaryClientAddresses.toString(), binaryClientAddresses, hasItem(clientAddress)));
        }
    }

    @Test
    public void shouldRecordTheClientAddressOfARequestThroughASocks5Tunnel() throws Exception {
        String clientAddress;
        try (Socket socket = connectTo(proxy)) {
            clientAddress = addressOf(socket);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[]{5, 1, 0});
            out.flush();
            assertThat(readBytes(in, 2), is(new byte[]{5, 0}));
            int port = upstream.getLocalPort();
            out.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
            out.flush();
            byte[] reply = readBytes(in, 4);
            assertThat("SOCKS5 CONNECT succeeded", reply[1], is((byte) 0));
            int addressLength = reply[3] == 1 ? 4 : reply[3] == 4 ? 16 : readBytes(in, 1)[0] & 0xff;
            readBytes(in, addressLength + 2);

            write(socket, get("/tunnelled/socks"));

            assertThat(readHead(socket), startsWith("HTTP/1.1 200"));
        }
        assertThat(recordedRemoteAddress(proxy, "/tunnelled/socks"), is(clientAddress));
    }

    @Test
    public void shouldIgnoreClientAddressHeadersSentThroughATunnel() throws Exception {
        String clientAddress;
        try (Socket socket = connectTo(proxy)) {
            clientAddress = addressOf(socket);
            openConnectTunnel(socket, upstream.getLocalPort());

            write(socket, "GET /tunnelled/spoofed HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + upstream.getLocalPort() + "\r\n"
                + "X-Forwarded-For: 203.0.113.7\r\n"
                + "X-Real-IP: 203.0.113.7\r\n"
                + "Forwarded: for=203.0.113.7\r\n"
                + "Content-Length: 0\r\n\r\n");

            assertThat(readHead(socket), startsWith("HTTP/1.1 200"));
        }
        assertThat(recordedRemoteAddress(proxy, "/tunnelled/spoofed"), is(clientAddress));
    }

    @Test
    public void shouldRecordTheSourceOfATrustedProxyProtocolHeaderForARequestThroughTheTunnelItOpened() throws Exception {
        try (Socket socket = connectTo(trustingProxy)) {
            write(socket, "PROXY TCP4 198.51.100.9 127.0.0.1 40000 " + trustingProxy.getLocalPort() + "\r\n");
            openConnectTunnel(socket, upstream.getLocalPort());

            write(socket, get("/tunnelled/proxy-protocol"));

            assertThat(readHead(socket), startsWith("HTTP/1.1 200"));
        }
        assertThat(recordedRemoteAddress(trustingProxy, "/tunnelled/proxy-protocol"), is("198.51.100.9:40000"));
    }

    private static String recordedRemoteAddress(ClientAndServer server, String path) throws IOException {
        List<String> remoteAddresses = recordedRemoteAddresses(server, request().withPath(path));
        assertThat(remoteAddresses, hasSize(1));
        return remoteAddresses.get(0);
    }

    /** From the requests' log entries: a retrieved request leaves out the address it was received from. */
    private static List<String> recordedRemoteAddresses(ClientAndServer server, HttpRequest matching) throws IOException {
        List<String> remoteAddresses = new ArrayList<>();
        for (JsonNode entry : OBJECT_MAPPER.readTree(server.retrieveRecordedRequests(matching, Format.LOG_ENTRIES))) {
            remoteAddresses.add(entry.path("httpRequest").path("remoteAddress").asText(null));
        }
        return remoteAddresses;
    }

    private static String addressOf(Socket socket) {
        return "127.0.0.1:" + socket.getLocalPort();
    }

    private static void openConnectTunnel(Socket socket, int port) throws IOException {
        write(socket, "CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n");
        assertThat(readHead(socket), startsWith("HTTP/1.1 200"));
    }

    private static String get(String path) {
        return "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + upstream.getLocalPort() + "\r\nContent-Length: 0\r\n\r\n";
    }

    private static Socket connectTo(ClientAndServer server) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), READ_TIMEOUT_MILLIS);
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

    /** The status line and headers of the next response, its body read past. */
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

    private static void echoOneConnection(ServerSocket server) {
        try (Socket connection = server.accept()) {
            byte[] buffer = new byte[1024];
            for (int read; (read = connection.getInputStream().read(buffer)) >= 0; ) {
                connection.getOutputStream().write(buffer, 0, read);
                connection.getOutputStream().flush();
            }
        } catch (IOException closed) {
            // the relay or the test closed the connection
        }
    }
}

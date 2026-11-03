package org.mockserver.netty.integration.proxy.http;

import org.junit.Test;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assume.assumeTrue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.LocalBoundIpAddresses.anotherAddressOfThisHost;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A CONNECT tunnel reaches MockServer through its own listener on the address {@code localBoundIP} binds. The relay
 * must not dial the wildcard address: a dual-stack epoll socket on Linux dials it as ::1 and the JDK's NIO as
 * 127.0.0.1, so each of these listeners would refuse it on some platform.
 */
public class ConnectRelayLocalBoundIpIntegrationTest {

    private static final int TIMEOUT_MILLIS = 10_000;

    @Test
    public void shouldTunnelThroughMockServerBoundToTheIpv4Loopback() throws Exception {
        assertTunnelsThrough(InetAddress.getByName("127.0.0.1"));
    }

    @Test
    public void shouldTunnelThroughMockServerBoundToTheIpv6Loopback() throws Exception {
        InetAddress ipv6Loopback = InetAddress.getByName("::1");
        assumeTrue("this host has no IPv6 loopback", NetworkInterface.getByInetAddress(ipv6Loopback) != null);
        assertTunnelsThrough(ipv6Loopback);
    }

    @Test
    public void shouldTunnelThroughMockServerBoundToAnotherAddressOfThisHost() throws Exception {
        assertTunnelsThrough(anotherAddressOfThisHost());
    }

    private static void assertTunnelsThrough(InetAddress boundAddress) throws Exception {
        MockServer mockServer = new MockServer(configuration().localBoundIP(boundAddress.getHostAddress()));
        try {
            int port = mockServer.getLocalPort();
            InetSocketAddress listener = new InetSocketAddress(boundAddress, port);
            String expectation = "{\"httpRequest\":{\"path\":\"/tunnelled\"},\"httpResponse\":{\"statusCode\":200,\"body\":\"tunnelled\"}}";
            try (Socket controlPlane = connect(listener)) {
                send(controlPlane, "PUT /mockserver/expectation HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: " + expectation.length() + "\r\n\r\n" + expectation);
                assertThat(readResponse(controlPlane), startsWith("HTTP/1.1 201"));
            }
            try (Socket tunnel = connect(listener)) {
                send(tunnel, "CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n");
                assertThat(readResponse(tunnel), startsWith("HTTP/1.1 200"));
                send(tunnel, "GET /tunnelled HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");
                String response = readResponse(tunnel);
                assertThat(response, startsWith("HTTP/1.1 200"));
                assertThat(response, endsWith("tunnelled"));
            }
        } finally {
            stopQuietly(mockServer);
        }
    }

    private static Socket connect(InetSocketAddress listener) throws IOException {
        Socket socket = new Socket(Proxy.NO_PROXY);
        socket.connect(listener, TIMEOUT_MILLIS);
        socket.setSoTimeout(TIMEOUT_MILLIS);
        return socket;
    }

    private static void send(Socket socket, String message) throws IOException {
        OutputStream outputStream = socket.getOutputStream();
        outputStream.write(message.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /**
     * @return one response's head and its {@code Content-Length} body
     */
    private static String readResponse(Socket socket) throws IOException {
        InputStream inputStream = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.UTF_8).endsWith("\r\n\r\n")) {
            int read = inputStream.read();
            if (read == -1) {
                return head.toString(StandardCharsets.UTF_8);
            }
            head.write(read);
        }
        String headText = head.toString(StandardCharsets.UTF_8);
        int contentLength = 0;
        for (String line : headText.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        return headText + new String(inputStream.readNBytes(contentLength), StandardCharsets.UTF_8);
    }
}

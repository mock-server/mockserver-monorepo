package org.mockserver.netty;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ManagementRelaySourcePolicyTest {
    private MockServer server;
    private HttpServer origin;

    @Before
    public void startOrigin() throws Exception {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/data", exchange -> {
            byte[] body = "origin data".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
    }

    @After
    public void stop() {
        if (server != null) {
            server.stop();
        }
        origin.stop(0);
    }

    private void startServer() {
        Configuration configuration = Configuration.configuration().localBoundIP("127.0.0.1")
            .controlPlaneAllowedCidrs("127.0.0.1/32").logLevel(Level.ERROR)
            .nioEventLoopThreadCount(2).clientNioEventLoopThreadCount(2);
        server = new MockServer(configuration, 0);
    }

    @Test
    public void connectKeepsTheOriginalAllowedOrDeniedClient() throws Exception {
        startServer();
        for (boolean denied : new boolean[]{false, true}) {
            assertThat("denied=" + denied, tunnel(denied, "", 1, false, true), containsString(denied ? "403 Forbidden" : "200 OK"));
            assertThat(tunnel(denied, "", 1, false, false), containsString("origin data"));
        }
    }

    @Test
    public void socksKeepsTheOriginalAllowedOrDeniedClient() throws Exception {
        startServer();
        for (boolean denied : new boolean[]{false, true}) {
            assertThat(tunnel(denied, "", 0, true, true), containsString(denied ? "403 Forbidden" : "200 OK"));
            assertThat(tunnel(denied, "", 0, true, false), containsString("origin data"));
        }
    }

    private Socket client(boolean denied) throws Exception {
        try {
            Socket socket = denied
                ? new Socket("127.0.0.1", server.getLocalPort(), InetAddress.getByName("127.0.0.2"), 0)
                : new Socket("127.0.0.1", server.getLocalPort());
            socket.setSoTimeout(10000);
            return socket;
        } catch (BindException unavailable) {
            if (denied && unavailable.getMessage().contains("Cannot assign requested address")) {
                Assume.assumeNoException("alternate IPv4 loopback is unavailable on this operating system", unavailable);
            }
            throw unavailable;
        }
    }

    private String tunnel(boolean denied, String prefix, int connectDepth, boolean socks, boolean management) throws Exception {
        try (Socket socket = client(denied)) {
            socket.getOutputStream().write(prefix.getBytes(StandardCharsets.US_ASCII));
            if (socks) {
                socket.getOutputStream().write(new byte[]{5, 1, 0});
                socket.getOutputStream().flush();
                assertThat(socket.getInputStream().readNBytes(2), is(new byte[]{5, 0}));
                int port = origin.getAddress().getPort();
                socket.getOutputStream().write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >>> 8), (byte) port});
                socket.getOutputStream().flush();
                byte[] reply = socket.getInputStream().readNBytes(4);
                assertThat(reply.length, is(4));
                assertThat(reply[1], is((byte) 0));
                int length = reply[3] == 1 ? 4 : reply[3] == 4 ? 16 : socket.getInputStream().read();
                assertThat(socket.getInputStream().readNBytes(length + 2).length, is(length + 2));
            }
            String authority = "127.0.0.1:" + origin.getAddress().getPort();
            for (int depth = 0; depth < connectDepth; depth++) {
                socket.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertThat(readHead(socket.getInputStream()), containsString("200"));
            }
            String request = (management ? "PUT /status" : "GET /data") + " HTTP/1.1\r\nHost: " + authority
                + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readHead(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (!bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int next = input.read();
            assertThat("CONNECT ended before its response", next, greaterThanOrEqualTo(0));
            bytes.write(next);
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }
}

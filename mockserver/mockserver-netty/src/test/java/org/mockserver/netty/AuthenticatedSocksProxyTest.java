package org.mockserver.netty;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.slf4j.event.Level;

import javax.net.ssl.SSLSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;
import static org.mockserver.configuration.Configuration.configuration;

public class AuthenticatedSocksProxyTest {
    private MockServer server;
    private HttpServer origin;
    private HttpsServer secureOrigin;
    private final AtomicInteger hits = new AtomicInteger();

    @Before
    public void start() throws Exception {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secureOrigin = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secureOrigin.setHttpsConfigurator(new HttpsConfigurator(new KeyStoreFactory(configuration(), new MockServerLogger()).sslContext()));
        for (HttpServer target : new HttpServer[]{origin, secureOrigin}) {
            target.createContext("/", exchange -> {
                hits.incrementAndGet();
                byte[] body = ("origin " + exchange.getRequestMethod() + " " + exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            target.start();
        }
        server = new MockServer(settings().controlPlanePort(0), 0);
    }

    private Configuration settings() {
        return configuration().localBoundIP("127.0.0.1").proxyAuthenticationUsername("u").proxyAuthenticationPassword("p")
            .nioEventLoopThreadCount(2).clientNioEventLoopThreadCount(2).logLevel(Level.ERROR);
    }

    @After
    public void stop() {
        if (server != null) {
            server.stop();
        }
        origin.stop(0);
        secureOrigin.stop(0);
    }

    private Socket client(int port) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(10000);
        return socket;
    }

    private void authenticate(Socket socket) throws Exception {
        socket.getOutputStream().write(new byte[]{5, 2, 0, 2});
        assertArrayEquals(new byte[]{5, 2}, socket.getInputStream().readNBytes(2));
        socket.getOutputStream().write(new byte[]{1, 1, 'u', 1, 'p'});
        assertArrayEquals(new byte[]{1, 0}, socket.getInputStream().readNBytes(2));
    }

    private byte[] connect(int targetPort) {
        return new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (targetPort >>> 8), (byte) targetPort};
    }

    private void readConnect(Socket socket) throws Exception {
        byte[] response = socket.getInputStream().readNBytes(4);
        assertEquals(4, response.length);
        assertEquals(0, response[1]);
        int size = response[3] == 1 ? 4 : response[3] == 4 ? 16 : socket.getInputStream().read();
        assertEquals(size + 2, socket.getInputStream().readNBytes(size + 2).length);
    }

    private String request(Socket socket, int targetPort, String method, String path) throws Exception {
        socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + targetPort
            + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    public void authenticatedHttpAndHttpsNeedNoHttpProxyAuthorization() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            int target = (tls ? secureOrigin : origin).getAddress().getPort();
            try (Socket socket = client(server.getLocalPort())) {
                authenticate(socket);
                socket.getOutputStream().write(connect(target));
                readConnect(socket);
                if (tls) {
                    try (SSLSocket secure = (SSLSocket) MockServerCaTrustTestSupport.caTrustingSslContext().getSocketFactory()
                        .createSocket(socket, "127.0.0.1", target, true)) {
                        secure.startHandshake();
                        assertThat(request(secure, target, "GET", "/socks"), containsString("origin GET /socks"));
                    }
                } else {
                    assertThat(request(socket, target, "GET", "/socks"), containsString("origin GET /socks"));
                }
            }
        }
        assertEquals(2, hits.get());
    }

    @Test
    public void rejectsNoAuthWrongPasswordSocks4AndManagementPortWithoutOriginHits() throws Exception {
        for (int port : new int[]{server.getLocalPort(), server.getControlPlanePort()}) {
            try (Socket socket = client(port)) {
                socket.getOutputStream().write(new byte[]{5, 1, 0});
                byte[] response = socket.getInputStream().readNBytes(2);
                if (port == server.getLocalPort()) {
                    assertArrayEquals(new byte[]{5, (byte) 0xff}, response);
                } else {
                    assertEquals(0, response.length);
                }
                assertEquals(-1, socket.getInputStream().read());
            }
            try (Socket socket = client(port)) {
                socket.getOutputStream().write(new byte[]{4, 1, 0, 80, 127, 0, 0, 1, 0});
                assertEquals(-1, socket.getInputStream().read());
            }
        }
        try (Socket socket = client(server.getLocalPort())) {
            socket.getOutputStream().write(new byte[]{5, 1, 2});
            assertArrayEquals(new byte[]{5, 2}, socket.getInputStream().readNBytes(2));
            socket.getOutputStream().write(new byte[]{1, 1, 'u', 1, 'x'});
            assertArrayEquals(new byte[]{1, (byte) 0xff}, socket.getInputStream().readNBytes(2));
            assertEquals(-1, socket.getInputStream().read());
        }
        assertEquals(0, hits.get());
    }

    @Test
    public void privateNetworkPolicyBlocksAfterSocksReplyBeforeAnyOriginConnection() throws Exception {
        server.stop();
        server = new MockServer(settings().controlPlanePort(0).forwardProxyBlockPrivateNetworks(true), 0);
        try (Socket socket = client(server.getLocalPort())) {
            authenticate(socket);
            socket.getOutputStream().write(connect(origin.getAddress().getPort()));
            readConnect(socket);
            assertThat(request(socket, origin.getAddress().getPort(), "GET", "/blocked"), containsString("502"));
        }
        assertEquals(0, hits.get());
    }

    @Test
    public void managementLookingRequestsStayOnTheProxyRoleAndOriginalCombinedSourceIsRetained() throws Exception {
        try (Socket socket = client(server.getLocalPort())) {
            authenticate(socket);
            socket.getOutputStream().write(connect(origin.getAddress().getPort()));
            readConnect(socket);
            assertThat(request(socket, origin.getAddress().getPort(), "PUT", "/mockserver/status"), containsString("origin PUT /mockserver/status"));
        }
        server.stop();
        server = new MockServer(settings().controlPlaneAllowedCidrs("192.0.2.0/24"), 0);
        try (Socket socket = client(server.getLocalPort())) {
            authenticate(socket);
            socket.getOutputStream().write(connect(server.getLocalPort()));
            readConnect(socket);
            assertThat(request(socket, server.getLocalPort(), "PUT", "/mockserver/status"), containsString("403"));
        }
        assertEquals(1, hits.get());
    }
    @Test
    public void wrongPasswordWithCoalescedConnectAndHttpNeverReachesOrigin() throws Exception {
        int port = origin.getAddress().getPort();
        try (Socket socket = client(server.getLocalPort())) {
            java.io.ByteArrayOutputStream frames = new java.io.ByteArrayOutputStream();
            frames.write(new byte[]{5, 1, 2, 1, 1, 'u', 1, 'x'});
            frames.write(connect(port));
            frames.write(("GET /never HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(frames.toByteArray());
            // A coalesced malformed greeting may be refused before SOCKS negotiation begins.
            assertEquals(-1, socket.getInputStream().read());
            assertEquals(-1, socket.getInputStream().read());
        }
        assertEquals(0, hits.get());
    }

}

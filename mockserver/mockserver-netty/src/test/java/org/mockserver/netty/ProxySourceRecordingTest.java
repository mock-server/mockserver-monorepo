package org.mockserver.netty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.integration.Http2TestClient;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.slf4j.event.Level;

import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertArrayEquals;
import static org.mockserver.configuration.Configuration.configuration;

public class ProxySourceRecordingTest {
    private static final String AUTHORIZATION = "Basic dTpw";
    private MockServer proxy;
    private HttpServer origin;
    private HttpsServer secureOrigin;
    private Path archive;

    @Before
    public void startOrigins() throws Exception {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secureOrigin = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secureOrigin.setHttpsConfigurator(new HttpsConfigurator(new KeyStoreFactory(configuration(), new MockServerLogger()).sslContext()));
        for (HttpServer target : new HttpServer[]{origin, secureOrigin}) {
            target.createContext("/source-test", exchange -> {
                byte[] body = "original client".getBytes(StandardCharsets.US_ASCII);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            target.start();
        }
    }

    private void startProxy() throws Exception {
        startProxy(false);
    }

    private void startProxy(boolean transparent) throws Exception {
        archive = Files.createTempFile("proxy-source", ".ndjson");
        proxy = new MockServer(configuration().localBoundIP("127.0.0.1").controlPlanePort(0)
            .controlPlaneAllowedCidrs("127.0.0.1/32")
            .transparentProxyEnabled(transparent)
            .proxyAuthenticationUsername("u").proxyAuthenticationPassword("p")
            .persistRecordedRequestsToDisk(true).persistedRecordedRequestsPath(archive.toString())
            .forwardProxyHttp2Enabled(false).nioEventLoopThreadCount(2).clientNioEventLoopThreadCount(2)
            .logLevel(Level.ERROR), 0);
    }

    @After
    public void stop() throws Exception {
        if (proxy != null) {
            proxy.stop();
        }
        origin.stop(0);
        secureOrigin.stop(0);
        if (archive != null) {
            Files.deleteIfExists(archive);
        }
    }

    private Socket client(String prefix) throws Exception {
        Socket socket = new Socket("127.0.0.1", proxy.getLocalPort());
        socket.setSoTimeout(10000);
        socket.getOutputStream().write(prefix.getBytes(StandardCharsets.US_ASCII));
        return socket;
    }

    private void write(Socket socket, String text) throws Exception {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private void request(Socket socket, int targetPort, boolean absolute) throws Exception {
        request(socket, targetPort, absolute, false);
    }

    private void request(Socket socket, int targetPort, boolean absolute, boolean keepAlive) throws Exception {
        String authority = "127.0.0.1:" + targetPort;
        write(socket, "GET " + (absolute ? "http://" + authority : "") + "/source-test HTTP/1.1\r\nHost: "
            + authority + "\r\nProxy-Authorization: " + AUTHORIZATION
            + "\r\nX-Forwarded-For: 203.0.113.55\r\nFly-Client-IP: 203.0.113.55\r\nConnection: "
            + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n");
        if (keepAlive) {
            String head = readHead(socket);
            assertThat(head, containsString("200"));
            assertThat(head.toLowerCase(java.util.Locale.ROOT), containsString("content-length: 15"));
            assertThat(new String(socket.getInputStream().readNBytes(15), StandardCharsets.UTF_8), is("original client"));
        } else {
            assertThat(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8), containsString("original client"));
        }
    }

    private void connect(Socket socket, int targetPort) throws Exception {
        String authority = "127.0.0.1:" + targetPort;
        write(socket, "CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority
            + "\r\nProxy-Authorization: " + AUTHORIZATION + "\r\n\r\n");
        assertThat(readHead(socket), containsString("200"));
    }

    private String readHead(Socket socket) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int next = socket.getInputStream().read();
            assertThat(next, greaterThanOrEqualTo(0));
            head.write(next);
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    private void socks(Socket socket, int targetPort) throws Exception {
        socket.getOutputStream().write(new byte[]{5, 1, 2});
        assertArrayEquals(new byte[]{5, 2}, socket.getInputStream().readNBytes(2));
        socket.getOutputStream().write(new byte[]{1, 1, 'u', 1, 'p'});
        assertArrayEquals(new byte[]{1, 0}, socket.getInputStream().readNBytes(2));
        socket.getOutputStream().write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (targetPort >>> 8), (byte) targetPort});
        byte[] response = socket.getInputStream().readNBytes(4);
        assertThat(response.length, is(4));
        assertThat(response[1], is((byte) 0));
        int length = response[3] == 1 ? 4 : response[3] == 4 ? 16 : socket.getInputStream().read();
        assertThat(socket.getInputStream().readNBytes(length + 2).length, is(length + 2));
    }

    private void assertRecorded(String expected) throws Exception {
        proxy.stop();
        proxy = null;
        List<JsonNode> requests = new ArrayList<>();
        for (String line : Files.readAllLines(archive)) {
            JsonNode request = new ObjectMapper().readTree(line).path("httpRequest");
            if ("/source-test".equals(request.path("path").asText())) {
                requests.add(request);
            }
        }
        assertThat(requests.size(), is(1));
        if (expected == null) {
            assertThat(requests.get(0).path("remoteAddress").asText(), startsWith("127.0.0.1:"));
        } else {
            assertThat(requests.get(0).path("remoteAddress").asText(), is(expected));
        }
        Files.delete(archive);
        archive = null;
    }

    @Test
    public void recordsTransparentProxySourceMetadata() throws Exception {
        startProxy(true);
        String header = "PROXY TCP4 192.0.2.5 127.0.0.1 43123 " + origin.getAddress().getPort() + "\r\n";
        try (Socket socket = client(header)) {
            request(socket, 9, false);
        }
        assertRecorded("192.0.2.5:43123");
    }

    @Test
    public void recordsDirectClientAcrossConnect() throws Exception {
        startProxy();
        int port;
        try (Socket socket = client("")) {
            port = socket.getLocalPort();
            connect(socket, origin.getAddress().getPort());
            request(socket, origin.getAddress().getPort(), false);
        }
        assertRecorded("127.0.0.1:" + port);
    }

    @Test
    public void recordsSourceAcrossSocksHttpAndHttps() throws Exception {
        for (boolean secure : new boolean[]{false, true}) {
            startProxy();
            int targetPort = (secure ? secureOrigin : origin).getAddress().getPort();
            try (Socket socket = client("")) {
                socks(socket, targetPort);
                if (secure) {
                    try (SSLSocket tls = (SSLSocket) MockServerCaTrustTestSupport.caTrustingSslContext().getSocketFactory()
                        .createSocket(socket, "127.0.0.1", targetPort, false)) {
                        tls.startHandshake();
                        request(tls, targetPort, false);
                    }
                } else {
                    request(socket, targetPort, false);
                }
            }
            assertRecorded(null);
        }
    }

    @Test
    public void recordsSourceOnDirectConnectAndSocksHttp2Streams() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            for (String transport : new String[]{"direct", "connect", "socks"}) {
                startProxy();
                boolean tunnel = !transport.equals("direct");
                Http2TestClient client;
                if (transport.equals("socks")) {
                    client = Http2TestClient.throughSocks5(group, proxy.getLocalPort(), "127.0.0.1", secureOrigin.getAddress().getPort(),
                        true, "u", "p", "");
                } else if (transport.equals("connect")) {
                    client = Http2TestClient.throughConnect(group, proxy.getLocalPort(), "127.0.0.1", secureOrigin.getAddress().getPort(),
                        true, AUTHORIZATION, "");
                } else {
                    client = Http2TestClient.h2c(group, proxy.getLocalPort(), "");
                }
                try (client) {
                    int targetPort = (tunnel ? secureOrigin : origin).getAddress().getPort();
                    Http2TestClient.Exchange response = client.send(new DefaultHttp2Headers().method("GET")
                        .scheme(tunnel ? "https" : "http").authority("127.0.0.1:" + targetPort).path("/source-test")
                        .add("proxy-authorization", AUTHORIZATION), true);
                    assertThat(response.status(), is(200));
                }
                assertRecorded(null);
            }
        } finally {
            group.shutdownGracefully().sync();
        }
    }
}

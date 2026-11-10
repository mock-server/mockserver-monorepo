package org.mockserver.netty;

import com.sun.net.httpserver.HttpServer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.http3.Http3TestClient;
import org.mockserver.netty.http3.Http3TestServer;
import org.mockserver.netty.integration.Http2TestClient;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;

public class CombinedControlPlaneSourcePolicyTest {
    private MockServer server;
    private HttpServer origin;

    @Before
    public void startOrigin() throws Exception {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            byte[] body = ("origin " + exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
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

    private Configuration configuration() {
        return Configuration.configuration().localBoundIP("127.0.0.1").nioEventLoopThreadCount(2)
            .clientNioEventLoopThreadCount(2).logLevel(Level.ERROR).mcpEnabled(true);
    }

    private String originHost() {
        return "127.0.0.1:" + origin.getAddress().getPort();
    }

    private String exchange(int port, String prefix, String method, String target, String headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            String request = prefix + method + " " + target + " HTTP/1.1\r\nHost: " + originHost()
                + "\r\nConnection: close\r\nContent-Length: 0\r\n" + headers + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void allowsSourcePolicyWithTransparentProxy() throws Exception {
        server = new MockServer(configuration().transparentProxyEnabled(true).proxyProtocolTrustedSources("127.0.0.1").controlPlaneAllowedCidrs("127.0.0.1/32"), 0);
        assertThat(exchange(server.getLocalPort(), "", "PUT", "/status", ""), containsString("200 OK"));
        String destination = "PROXY TCP4 198.51.100.5 127.0.0.1 12345 " + origin.getAddress().getPort() + "\r\n";
        assertThat(exchange(server.getLocalPort(), destination, "GET", "/ordinary", ""), containsString("origin /ordinary"));
        server.stop();
        server = new MockServer(configuration().transparentProxyEnabled(true).proxyProtocolTrustedSources("127.0.0.1").controlPlaneAllowedCidrs("192.0.2.0/24"), 0);
        String untrustedSource = "PROXY TCP4 192.0.2.5 127.0.0.1 12345 " + server.getLocalPort() + "\r\n";
        assertThat(exchange(server.getLocalPort(), untrustedSource, "PUT", "/status", ""), containsString("403 Forbidden"));
    }

    @Test
    public void combinedDefaultAndExplicitAllowedSourceServeManagement() throws Exception {
        for (String cidrs : new String[]{null, "127.0.0.1/32"}) {
            server = new MockServer(configuration().controlPlaneAllowedCidrs(cidrs), 0);
            assertThat(exchange(server.getLocalPort(), "", "PUT", "/status", ""), containsString("200 OK"));
            server.stop();
        }
    }

    @Test
    public void combinedDeniesManagementButForwardsDataAndDoesNotTrustHttpHeaders() throws Exception {
        Configuration configuration = configuration().controlPlaneAllowedCidrs("192.0.2.0/24").livenessHttpGetPath("/alive");
        server = new MockServer(configuration, 0);
        configuration.controlPlaneAllowedCidrs("127.0.0.1/32");
        for (String[] route : new String[][]{
            {"PUT", "/mockserver/status"}, {"PUT", "/status?probe=1"}, {"GET", "/ready"}, {"GET", "/alive"},
            {"GET", "/mockserver/configuration"}, {"GET", "/mockserver/dashboard"}, {"GET", "/mockserver/mcp"},
            {"GET", "/_mockserver_ui_websocket?limit=1"}, {"GET", "/_mockserver_callback_websocket"},
            {"PUT", "http://" + originHost() + "/mockserver/status?probe=1"}, {"GET", "/mockserver/reserved-unknown"}}) {
            assertThat(route[1], exchange(server.getLocalPort(), "", route[0], route[1],
                "X-Forwarded-For: 192.0.2.5\r\nFly-Client-IP: 192.0.2.5\r\n"), containsString("403 Forbidden"));
        }
        for (String path : new String[]{"/_mockserver_ui_websocket", "/_mockserver_callback_websocket"}) {
            assertThat(exchange(server.getLocalPort(), "", "GET", path,
                "Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"),
                containsString("403 Forbidden"));
        }
        assertThat(exchange(server.getLocalPort(), "", "OPTIONS", "/expectation",
            "Origin: https://example.test\r\nAccess-Control-Request-Method: PUT\r\n"), containsString("403 Forbidden"));
        for (String path : new String[]{"/ordinary", "/status", "/%6dockserver/status", "/%73tatus"}) {
            assertThat(path, exchange(server.getLocalPort(), "", "GET", "http://" + originHost() + path, ""),
                containsString("origin " + path));
        }
    }

    @Test
    public void separatedSourcePolicyLeavesProxyTrafficUnrestricted() throws Exception {
        server = new MockServer(configuration().controlPlanePort(0).controlPlaneAllowedCidrs("192.0.2.0/24"), 0);
        try {
            assertThat(exchange(server.getControlPlanePort(), "", "PUT", "/status", ""), is(""));
        } catch (java.net.SocketException closedBeforeRead) {
            assertThat(closedBeforeRead.getMessage(), containsString("Connection reset"));
        }
        assertThat(exchange(server.getLocalPort(), "", "PUT", "http://" + originHost() + "/mockserver/status", ""),
            containsString("origin /mockserver/status"));
    }

    @Test
    public void http2ManagementDenialsDoNotBlockOtherStreams() throws Exception {
        server = new MockServer(configuration().controlPlaneAllowedCidrs("192.0.2.0/24"), 0);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try (Http2TestClient client = Http2TestClient.h2c(group, server.getLocalPort())) {
            for (String path : new String[]{"/status", "/mockserver/mcp", "/_mockserver_ui_websocket", "/_mockserver_callback_websocket"}) {
                assertThat(client.send(new DefaultHttp2Headers().method(path.equals("/status") ? "PUT" : "GET")
                    .scheme("http").authority(originHost()).path(path), true).status(), is(403));
            }
            for (boolean mixedCase : new boolean[]{false, true}) {
                DefaultHttp2Headers preflight = new DefaultHttp2Headers(false);
                preflight.method("OPTIONS").scheme("http").authority(originHost()).path("/expectation");
                preflight.add(mixedCase ? "Origin" : "origin", "http://localhost");
                preflight.add(mixedCase ? "Access-Control-Request-Method" : "access-control-request-method", "PUT");
                assertThat(client.send(preflight, true).status(), is(403));
            }
            DefaultHttp2Headers putFirst = new DefaultHttp2Headers(false);
            putFirst.method("OPTIONS").scheme("http").authority(originHost()).path("/expectation");
            putFirst.add("origin", "http://localhost").add("Access-Control-Request-Method", "PUT")
                .add("access-control-request-method", "GET");
            assertThat(client.send(putFirst, true).status(), is(403));
            DefaultHttp2Headers getFirst = new DefaultHttp2Headers(false);
            getFirst.method("OPTIONS").scheme("http").authority(originHost()).path("/expectation");
            getFirst.add("origin", "http://localhost").add("access-control-request-method", "GET")
                .add("Access-Control-Request-Method", "PUT");
            assertThat(client.send(getFirst, true).status(), is(200));
            assertThat(client.send(new DefaultHttp2Headers().method("GET").scheme("http").authority(originHost())
                .path("/ordinary"), true).body(), is("origin /ordinary"));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void http3ManagementAndMcpAreDeniedBeforeDispatchWhileDataWorks() throws Exception {
        boolean available;
        try {
            available = io.netty.handler.codec.quic.Quic.isAvailable();
        } catch (Throwable unavailable) {
            available = false;
        }
        Assume.assumeTrue("native QUIC transport not available", available);
        server = Http3TestServer.startWithHttp3(configuration().controlPlaneAllowedCidrs("192.0.2.0/24")
            .initializationClass(DataInitializer.class.getName()));
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try (Http3TestClient client = Http3TestClient.open(group, server)) {
            for (String path : new String[]{"/mockserver/configuration", "/mockserver/mcp", "/ready"}) {
                assertThat(client.send(new DefaultHttp3Headers().method("GET").scheme("https")
                    .authority(originHost()).path(path)).status(), is(403));
            }
            assertThat(client.send(new DefaultHttp3Headers().method("OPTIONS").scheme("https")
                .authority(originHost()).path("/expectation").set("origin", "http://localhost")
                .set("access-control-request-method", "PUT")).status(), is(403));
            assertThat(client.send(new DefaultHttp3Headers().method("GET").scheme("https")
                .authority(originHost()).path("/ordinary")).body(), is("mocked data"));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
        server.stop();
        server = Http3TestServer.startWithHttp3(configuration().controlPlaneAllowedCidrs("127.0.0.1/32"));
        NioEventLoopGroup allowedGroup = new NioEventLoopGroup(1);
        try (Http3TestClient client = Http3TestClient.open(allowedGroup, server)) {
            assertThat(client.send(new DefaultHttp3Headers().method("GET").scheme("https")
                .authority("localhost").path("/mockserver/mode")).status(), is(200));
        } finally {
            allowedGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    public static class DataInitializer implements org.mockserver.server.initialize.ExpectationInitializer {
        @Override
        public org.mockserver.mock.Expectation[] initializeExpectations() {
            return new org.mockserver.mock.Expectation[]{new org.mockserver.mock.Expectation(
                org.mockserver.model.HttpRequest.request().withPath("/ordinary"))
                .thenRespond(org.mockserver.model.HttpResponse.response().withBody("mocked data"))};
        }
    }
}

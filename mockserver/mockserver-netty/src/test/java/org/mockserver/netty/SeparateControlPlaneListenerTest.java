package org.mockserver.netty;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class SeparateControlPlaneListenerTest {
    private MockServer server;
    private HttpServer origin;
    private final java.util.concurrent.atomic.AtomicInteger originRequests = new java.util.concurrent.atomic.AtomicInteger();

    @Before
    public void start() throws Exception {
        server = new MockServer(Configuration.configuration().controlPlanePort(0).localBoundIP("127.0.0.1")
            .nioEventLoopThreadCount(2).clientNioEventLoopThreadCount(2).logLevel(Level.ERROR), 0);
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            originRequests.incrementAndGet();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String originResponse = "origin " + exchange.getRequestMethod() + " " + exchange.getRequestURI();
            if (authorization != null) {
                originResponse += " authorization=" + authorization;
            }
            byte[] body = originResponse.getBytes(StandardCharsets.UTF_8);
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
        if (origin != null) {
            origin.stop(0);
        }
    }

    private String exchange(int port, String bytes) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(bytes.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String proxyRequest(String method, String path, String headers) throws Exception {
        String host = "127.0.0.1:" + origin.getAddress().getPort();
        return exchange(server.getLocalPort(), method + " http://" + host + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n" + headers + "\r\n");
    }

    @Test
    public void forwardsManagementLookingPathsAndPreflightsToOrigin() throws Exception {
        for (String path : new String[]{"/mockserver/status", "/status", "/mockserver/configuration", "/mockserver/mcp", "/mockserver/dashboard", "/mockserver/dashboard/websocket", "/_mockserver_callback_websocket"}) {
            String response = proxyRequest("GET", path, "");
            assertThat(path, response, containsString("origin GET " + path));
        }
        String response = proxyRequest("OPTIONS", "/mockserver/expectation", "Origin: https://example.test\r\nAccess-Control-Request-Method: PUT\r\n");
        assertThat(response, containsString("origin OPTIONS /mockserver/expectation"));
    }

    @Test
    public void allResponseCorsAppliesToProxyPreflightsAndMatchedResponses() throws Exception {
        server.stop();
        server = new MockServer(Configuration.configuration().controlPlanePort(0).localBoundIP("127.0.0.1")
            .enableCORSForAllResponses(true).enableCORSForAPI(false)
            .nioEventLoopThreadCount(2).clientNioEventLoopThreadCount(2).logLevel(Level.ERROR), 0);
        String json = "{\"httpRequest\":{\"method\":\"GET\",\"path\":\"/shared\"},\"httpResponse\":{\"statusCode\":201,\"body\":\"shared-state\"}}";
        assertThat(exchange(server.getControlPlanePort(), "PUT /mockserver/expectation HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: " + json.length() + "\r\n\r\n" + json),
            containsString("201 Created"));
        String response = proxyRequest("OPTIONS", "/shared", "Origin: https://example.test\r\nAccess-Control-Request-Method: GET\r\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response.toLowerCase(java.util.Locale.ROOT), containsString("access-control-allow-origin: https://example.test"));
        assertThat(originRequests.get(), is(0));
        response = proxyRequest("GET", "/shared", "Origin: https://example.test\r\n");
        assertThat(response, containsString("shared-state"));
        assertThat(response.toLowerCase(java.util.Locale.ROOT), containsString("access-control-allow-origin: https://example.test"));
        assertThat(originRequests.get(), is(0));
    }

    @Test
    public void sharesLiveExpectationsBetweenManagementAndProxyListener() throws Exception {
        String json = "{\"httpRequest\":{\"path\":\"/shared\"},\"httpResponse\":{\"statusCode\":201,\"body\":\"shared-state\"}}";
        String response = exchange(server.getControlPlanePort(), "PUT /mockserver/expectation HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: " + json.length() + "\r\n\r\n" + json);
        assertThat(response, containsString("201 Created"));
        response = exchange(server.getLocalPort(), "GET /shared HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertThat(response, containsString("shared-state"));
        assertThat(response, containsString("201 Created"));
    }

    @Test
    public void managementNeverForwardsRequestsOrConnectTunnels() throws Exception {
        String response = exchange(server.getControlPlanePort(), "GET /unconfigured HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertThat(response, containsString("404 Not Found"));
        response = exchange(server.getControlPlanePort(), "CONNECT 127.0.0.1:" + origin.getAddress().getPort() + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString("405 Method Not Allowed"));
        response = exchange(server.getControlPlanePort(), "GET http://127.0.0.1:" + origin.getAddress().getPort() + "/ HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString("405 Method Not Allowed"));
    }

    @Test
    public void refusesClientSuppliedSourceAddressPreambles() throws Exception {
        server.stop();
        server = new MockServer(Configuration.configuration().controlPlanePort(0).localBoundIP("127.0.0.1")
            .transparentProxyEnabled(true).proxyProtocolTrustedSources("127.0.0.1")
            .nioEventLoopThreadCount(2).clientNioEventLoopThreadCount(2).logLevel(Level.ERROR), 0);
        String prefix = "PROXY TCP4 192.0.2.5 127.0.0.1 12345 1081\r\n";
        String response = exchange(server.getControlPlanePort(), prefix + "PUT /mockserver/status HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertThat(response, not(containsString("200 OK")));
    }

    @Test
    public void managementRestrictionsCannotChangeAtRuntime() throws Exception {
        String json = "{\"controlPlaneAllowedCidrs\":\"0.0.0.0/0\"}";
        String response = exchange(server.getControlPlanePort(), "PUT /mockserver/configuration HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: " + json.length() + "\r\n\r\n" + json);
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("127.0.0.0/8,::1/128"));
        assertThat(response, not(containsString("0.0.0.0/0")));
    }
    @Test
    public void managementAndProxyRemainIsolatedOnHttp2Streams() throws Exception {
        io.netty.channel.EventLoopGroup group = new io.netty.channel.nio.NioEventLoopGroup(1);
        try (org.mockserver.netty.integration.Http2TestClient management = org.mockserver.netty.integration.Http2TestClient.h2c(group, server.getControlPlanePort());
             org.mockserver.netty.integration.Http2TestClient proxy = org.mockserver.netty.integration.Http2TestClient.h2c(group, server.getLocalPort())) {
            String host = "127.0.0.1:" + origin.getAddress().getPort();
            for (String path : new String[]{"/mockserver/status", "/mockserver/mcp", "/mockserver/dashboard"}) {
                org.mockserver.netty.integration.Http2TestClient.Exchange response = proxy.send(new io.netty.handler.codec.http2.DefaultHttp2Headers()
                    .method("GET").scheme("http").authority(host).path(path), true);
                assertThat(response.status(), is(200));
                assertThat(response.body(), is("origin GET " + path));
            }
            org.mockserver.netty.integration.Http2TestClient.Exchange status = management.send(new io.netty.handler.codec.http2.DefaultHttp2Headers()
                .method("PUT").scheme("http").authority("localhost").path("/mockserver/status"), true);
            assertThat(status.status(), is(200));
            org.mockserver.netty.integration.Http2TestClient.Exchange unknown = management.send(new io.netty.handler.codec.http2.DefaultHttp2Headers()
                .method("GET").scheme("http").authority(host).path("/unconfigured"), true);
            assertThat(unknown.status(), is(404));
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    public void managementAuthenticationDoesNotAcceptProxyCredentials() throws Exception {
        server.stop();
        server = new MockServer(Configuration.configuration().controlPlanePort(0).localBoundIP("127.0.0.1")
            .controlPlaneBasicAuthenticationRequired(true).controlPlaneBasicAuthenticationAllowInsecure(true)
            .controlPlaneBasicAuthenticationCredentials("admin:" + org.mockserver.authentication.basic.PasswordHash.bcrypt("admin-secret", 4))
            .proxyAuthenticationUsername("proxy").proxyAuthenticationPassword("proxy-secret")
            .nioEventLoopThreadCount(2).logLevel(Level.ERROR), 0);
        String proxyCredentials = java.util.Base64.getEncoder().encodeToString("proxy:proxy-secret".getBytes(StandardCharsets.UTF_8));
        String response = exchange(server.getControlPlanePort(), "PUT /mockserver/status HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nProxy-Authorization: Basic " + proxyCredentials + "\r\n\r\n");
        assertThat(response, containsString("401 Unauthorized"));
        String adminCredentials = java.util.Base64.getEncoder().encodeToString("admin:admin-secret".getBytes(StandardCharsets.UTF_8));
        response = exchange(server.getControlPlanePort(), "PUT /mockserver/status HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nAuthorization: Basic " + adminCredentials + "\r\n\r\n");
        assertThat(response, containsString("200 OK"));
        response = proxyRequest("PUT", "/mockserver/stop", "Proxy-Authorization: Basic " + proxyCredentials + "\r\nContent-Length: 0\r\n");
        assertThat(response, containsString("origin PUT /mockserver/stop"));
    }

    @Test
    public void rejectsPublicRelayPreamblesAndSocksWithoutProxyAuthentication() throws Exception {
        String response = exchange(server.getLocalPort(), "PROXIED_127.0.0.1:" + origin.getAddress().getPort());
        assertThat(response, is(""));
        try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(new byte[]{5, 1, 0});
            socket.getOutputStream().flush();
            assertThat(socket.getInputStream().read(), is(-1));
        }
    }

    @Test
    public void recordsOriginAuthorizationOnManagementLookingPathsWithoutProxyLogin() throws Exception {
        String proxyLogin = java.util.Base64.getEncoder().encodeToString("proxy:ignored-proxy-secret".getBytes(StandardCharsets.UTF_8));
        String response = proxyRequest("GET", "/mockserver/configuration", "Authorization: Bearer origin-secret\r\nProxy-Authorization: Basic " + proxyLogin + "\r\n");
        assertThat(response, containsString("authorization=Bearer origin-secret"));
        String filter = "{\"path\":\"/mockserver/configuration\"}";
        response = exchange(server.getControlPlanePort(), "PUT /mockserver/retrieve?type=REQUEST_RESPONSES HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: " + filter.length() + "\r\n\r\n" + filter);
        assertThat(response, containsString("Bearer origin-secret"));
        assertThat(response, not(containsString(proxyLogin)));
        assertThat(response, not(containsString("Proxy-Authorization")));
    }

    @Test
    public void configuredRemoteRequiresAuthenticationAndStillForwardsAuthorizedHttp() throws Exception {
        server.stop();
        server = new MockServer(Configuration.configuration().controlPlanePort(0).localBoundIP("127.0.0.1")
            .proxyAuthenticationUsername("proxy").proxyAuthenticationPassword("proxy-secret")
            .nioEventLoopThreadCount(2).clientNioEventLoopThreadCount(2).logLevel(Level.ERROR),
            origin.getAddress().getPort(), "127.0.0.1", 0);
        String response = proxyRequest("GET", "/auth-target", "");
        assertThat(response, containsString("407 Proxy Authentication Required"));
        assertThat(originRequests.get(), is(0));
        String credentials = java.util.Base64.getEncoder().encodeToString("proxy:proxy-secret".getBytes(StandardCharsets.UTF_8));
        response = proxyRequest("GET", "/auth-target", "Proxy-Authorization: Basic " + credentials + "\r\n");
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("origin GET /auth-target"));
        assertThat(originRequests.get(), is(1));
    }

    @Test
    public void earlyDataPlaneAuthenticationFailureNeverLogsProxyCredentialsWithRedactionDisabled() throws Exception {
        assertEarlyRejectionLogs(Configuration.configuration().dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("required-data-token"), "401 Unauthorized");
    }

    @Test
    public void plaintextMutualTlsRejectionNeverLogsProxyCredentialsWithRedactionDisabled() throws Exception {
        assertEarlyRejectionLogs(Configuration.configuration().tlsMutualAuthenticationRequired(true)
            .tlsMutualAuthenticationAllowBundledCa(true), "426 Upgrade Required");
    }

    private void assertEarlyRejectionLogs(Configuration configuration, String expectedStatus) throws Exception {
        server.stop();
        server = new MockServer(configuration.controlPlanePort(0).localBoundIP("127.0.0.1")
            .proxyAuthenticationUsername("proxy").proxyAuthenticationPassword("proxy-secret")
            .redactSecretsInLog(false).logLevel(Level.INFO).nioEventLoopThreadCount(2).clientNioEventLoopThreadCount(2), 0);
        String credentials = java.util.Base64.getEncoder().encodeToString("proxy:proxy-secret".getBytes(StandardCharsets.UTF_8));
        String response = proxyRequest("GET", "/logged-rejection", "Proxy-Authorization: Basic " + credentials
            + "\r\nAuthorization: Bearer origin-visible\r\nCookie: session=origin-cookie\r\n");
        assertThat(response, containsString(expectedStatus));
        assertThat(originRequests.get(), is(0));
        String filter = "{\"path\":\"/logged-rejection\"}";
        String logs = exchange(server.getControlPlanePort(), "PUT /mockserver/retrieve?type=LOGS&format=LOG_ENTRIES HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: " + filter.length() + "\r\n\r\n" + filter);
        assertThat(logs, containsString("origin-visible"));
        assertThat(logs, containsString("origin-cookie"));
        assertThat(logs, not(containsString(credentials)));
        assertThat(logs, not(containsString("proxy-secret")));
    }

}

package org.mockserver.netty.controlplane;

import com.sun.net.httpserver.HttpServer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;
import org.mockserver.netty.proxy.relay.RelayConnectHandler;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

public class ConnectWireLoggingTest {
    private static final String LOGIN = "Basic cHJveHk6cHJveHktc2VjcmV0";
    private static final String INNER_LOGIN = "Basic aW5uZXI6aW5uZXItcHJveHktc2VjcmV0";

    @Test
    public void http1ConnectInnerCredentialsNeverEnterTraceDumps() throws Exception {
        assertConnectLogging(false);
    }

    @Test
    public void http2ConnectInnerCredentialsNeverEnterTraceDumps() throws Exception {
        assertConnectLogging(true);
    }

    private void assertConnectLogging(boolean http2) throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            boolean separate = mode != 0;
            boolean authenticated = mode != 2;
            AtomicInteger originRequests = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<String> forwardedProxyLogin = new java.util.concurrent.atomic.AtomicReference<>();
            HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            origin.createContext("/inner", exchange -> {
                originRequests.incrementAndGet();
                forwardedProxyLogin.set(exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
                byte[] body = (exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + exchange.getRequestHeaders().getFirst("Cookie")).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            origin.start();
            Configuration configuration = Configuration.configuration().mcpEnabled(false).logLevel(Level.TRACE)
                .redactSecretsInLog(false).nioEventLoopThreadCount(2).clientNioEventLoopThreadCount(2);
            if (separate) {
                configuration.controlPlanePort(0);
            }
            if (authenticated) {
                configuration.proxyAuthenticationUsername("proxy").proxyAuthenticationPassword("proxy-secret");
            }
            MockServer proxy = new MockServer(configuration, 0);
            EventLoopGroup group = new NioEventLoopGroup(1);
            try (RelayTraceCapture trace = new RelayTraceCapture()) {
                int targetPort = origin.getAddress().getPort();
                String authority = "127.0.0.1:" + targetPort;
                String body;
                if (http2) {
                    try (Http2TestClient client = Http2TestClient.throughConnect(group, proxy.getLocalPort(), "127.0.0.1", targetPort,
                        false, authenticated ? LOGIN : null)) {
                        Http2TestClient.Exchange exchange = client.send(new DefaultHttp2Headers().method("GET").scheme("http")
                            .authority(authority).path("/inner").add("proxy-authorization", INNER_LOGIN)
                            .add("authorization", "Bearer origin-visible").add("cookie", "session=origin-cookie"), true);
                        assertThat(exchange.status(), is(200));
                        body = exchange.body();
                    }
                } else {
                    try (Socket socket = new Socket("127.0.0.1", proxy.getLocalPort())) {
                        socket.setSoTimeout(10000);
                        socket.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n"
                            + (authenticated ? "Proxy-Authorization: " + LOGIN + "\r\n" : "") + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        ByteArrayOutputStream response = new ByteArrayOutputStream();
                        while (!response.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                            int next = socket.getInputStream().read();
                            assertThat("CONNECT response ended prematurely", next, greaterThanOrEqualTo(0));
                            response.write(next);
                        }
                        assertThat(response.toString(StandardCharsets.US_ASCII), containsString("200"));
                        socket.getOutputStream().write(("GET /inner HTTP/1.1\r\nHost: " + authority
                            + "\r\nProxy-Authorization: " + INNER_LOGIN + "\r\nAuthorization: Bearer origin-visible"
                            + "\r\nCookie: session=origin-cookie\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        body = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        assertThat(body, containsString("200"));
                    }
                }
                assertThat(body, containsString("origin-visible"));
                assertThat(body, containsString("origin-cookie"));
                assertThat(originRequests.get(), is(1));
                assertThat(forwardedProxyLogin.get(), is(nullValue()));
                try (MockServerClient admin = new MockServerClient("127.0.0.1", separate ? proxy.getControlPlanePort() : proxy.getLocalPort())) {
                    String logs = admin.retrieveLogMessages(request().withPath("/inner"));
                    assertThat(logs, containsString("origin-visible"));
                    assertThat(logs, containsString("origin-cookie"));
                    assertThat(logs, not(containsString(INNER_LOGIN)));
                    assertThat(logs, not(containsString("inner-proxy-secret")));
                    assertThat(logs, not(containsString(LOGIN)));
                }
                assertThat("raw relay and HTTP/2 frame loggers must remain absent", trace.messages, is(empty()));
            } finally {
                proxy.stop();
                origin.stop(0);
                group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
    }

    private static final class RelayTraceCapture implements AutoCloseable {
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final List<Logger> loggers = new ArrayList<>();
        private final List<java.util.logging.Level> levels = new ArrayList<>();
        private final Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };

        private RelayTraceCapture() {
            handler.setLevel(java.util.logging.Level.ALL);
            for (String suffix : new String[]{"", "-downstream -->", "-upstream <-- "}) {
                String name = RelayConnectHandler.class.getName() + suffix;
                Logger logger = Logger.getLogger(name);
                levels.add(logger.getLevel());
                logger.setLevel(java.util.logging.Level.ALL);
                logger.addHandler(handler);
                loggers.add(logger);
                LoggerFactory.getLogger(name).trace("relay trace capture is active");
                assertThat(messages, hasItem("relay trace capture is active"));
                messages.clear();
            }
        }

        @Override public void close() {
            for (int index = 0; index < loggers.size(); index++) {
                loggers.get(index).removeHandler(handler);
                loggers.get(index).setLevel(levels.get(index));
            }
        }
    }
}

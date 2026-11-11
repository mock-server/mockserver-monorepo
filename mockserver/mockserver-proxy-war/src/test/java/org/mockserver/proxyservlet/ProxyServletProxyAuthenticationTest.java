package org.mockserver.proxyservlet;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.model.HttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The proxy WAR proxies every request for another host, so with {@code proxyAuthenticationUsername} and
 * {@code proxyAuthenticationPassword} set such a request is refused with {@code 407} before it can match an
 * expectation (and so before it can use up {@code Times}). A request for the WAR itself needs no proxy credentials and
 * is a mock request, as on the Netty server: matched, or {@code 404}, never forwarded.
 */
public class ProxyServletProxyAuthenticationTest {

    private static final String USERNAME = "proxy-user";
    private static final String PASSWORD = "proxy-password";

    private ProxyServlet servlet;

    @After
    public void destroyServlet() {
        if (servlet != null) {
            servlet.destroy();
        }
    }

    private void start(Configuration configuration) {
        servlet = new ProxyServlet(configuration);
        MockHttpServletRequest upsert = new MockHttpServletRequest("PUT", "/mockserver/expectation");
        upsert.setContent("{\"httpRequest\":{\"path\":\"/once\"},\"times\":{\"remainingTimes\":1},\"httpResponse\":{\"body\":\"once\"}}".getBytes(UTF_8));
        assertThat(send(upsert).getStatus(), is(201));
    }

    private MockHttpServletResponse send(MockHttpServletRequest request) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.service(request, response);
        return response;
    }

    private static MockHttpServletRequest once(String host, String password) {
        return get("/once", host, password);
    }

    private static String body(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), UTF_8);
    }

    @Test
    public void shouldRefuseARequestForAnotherHostWithoutProxyCredentialsBeforeMatching() {
        start(configuration().proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD));

        MockHttpServletResponse none = send(once("example.com", null));
        assertThat(none.getStatus(), is(407));
        assertThat(none.getHeader("Proxy-Authenticate"), startsWith("Basic realm="));
        assertThat(send(once("example.com", "not-the-password")).getStatus(), is(407));

        MockHttpServletResponse served = send(once("example.com", PASSWORD));
        assertThat("Times still unused", served.getStatus(), is(200));
        assertThat(body(served), is("once"));
    }

    @Test
    public void shouldKeepTheProxyCredentialOutOfTheLogAndTheRecordedRequests() {
        start(configuration().proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD));
        String wrong = Base64.getEncoder().encodeToString((USERNAME + ":not-the-password").getBytes(UTF_8));
        String right = Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes(UTF_8));

        assertThat(send(once("example.com", "not-the-password")).getStatus(), is(407));
        assertThat(send(once("example.com", PASSWORD)).getStatus(), is(200));

        String logs = body(send(retrieve("LOGS")));
        String recorded = body(send(retrieve("REQUESTS")));
        assertThat(logs, not(containsString(wrong)));
        assertThat(logs, not(containsString(right)));
        assertThat(recorded, containsString("/once"));
        assertThat(recorded, not(containsString(right)));
    }

    private static MockHttpServletRequest retrieve(String type) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/mockserver/retrieve");
        request.setQueryString("type=" + type);
        request.addParameter("type", type);
        return request;
    }

    @Test
    public void shouldServeARequestForItselfWithoutProxyCredentials() {
        start(configuration().proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD));

        MockHttpServletResponse served = send(once("localhost", null));

        assertThat(served.getStatus(), is(200));
        assertThat(body(served), is("once"));
    }

    @Test
    public void shouldServeWithoutProxyCredentialsWhenNoneAreConfigured() {
        start(configuration());

        MockHttpServletResponse served = send(once("example.com", null));

        assertThat(served.getStatus(), is(200));
        assertThat(body(served), is("once"));
    }

    @Test
    public void shouldAnswerAnUnmatchedRequestForItselfWith404AndNotForwardIt() throws Exception {
        start(configuration().proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD));
        try (ConnectionCounter container = new ConnectionCounter()) {
            for (String host : new String[]{"localhost:" + container.port(), "127.0.0.1:" + container.port()}) {
                MockHttpServletResponse response = send(on(container.port(), get("/manager/text/list", host, null)));

                assertThat(host, response.getStatus(), is(404));
            }
            assertThat("connections to the WAR's own address", container.connected(), is(false));

            MockHttpServletResponse served = send(on(container.port(), get("/once", "localhost:" + container.port(), null)));
            assertThat(served.getStatus(), is(200));
            assertThat(body(served), is("once"));
        }
    }

    @Test
    public void shouldAnswerAnUnmatchedRequestForItselfWith404WithoutProxyAuthenticationConfigured() throws Exception {
        start(configuration());
        try (ConnectionCounter container = new ConnectionCounter()) {
            for (String host : new String[]{"localhost:" + container.port(), "127.0.0.1:" + container.port()}) {
                MockHttpServletResponse response = send(on(container.port(), get("/manager/text/list", host, null)));

                assertThat(host, response.getStatus(), is(404));
            }
            assertThat("connections to the WAR's own address", container.connected(), is(false));
        }
    }

    @Test
    public void shouldAnswerAnUnmatchedRequestWithoutHostWith404() throws Exception {
        start(configuration().proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD));
        try (ConnectionCounter container = new ConnectionCounter()) {
            MockHttpServletRequest request = on(container.port(), new MockHttpServletRequest("GET", "/manager/text/list"));
            request.setRemoteAddr("127.0.0.1");
            request.setRemotePort(container.port());

            MockHttpServletResponse response = send(request);

            assertThat(response.getStatus(), is(404));
            assertThat("connections to the WAR's own address", container.connected(), is(false));
        }
    }

    @Test
    public void shouldRequireProxyCredentialsToForwardAnUnmatchedRequestWithoutHostToProxyRemoteHost() throws Exception {
        // no Host skips the servlet's own 407; this one comes from the proxyRemoteHost forward a request for the WAR takes
        try (ConnectionCounter remote = new ConnectionCounter()) {
            start(configuration()
                .proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD)
                .proxyRemoteHost("127.0.0.1").proxyRemotePort(remote.port()));

            MockHttpServletResponse refused = send(new MockHttpServletRequest("GET", "/manager/text/list"));

            assertThat(refused.getStatus(), is(407));
            assertThat("connections to proxyRemoteHost", remote.connected(), is(false));
        }
    }

    @Test
    public void shouldForwardAnUnmatchedRequestForAnotherHostOnlyWithProxyCredentials() {
        start(configuration().proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD));
        EchoServer upstream = new EchoServer(false);
        try {
            String host = "127.0.0.1:" + upstream.getPort();

            assertThat(send(get("/upstream", host, null)).getStatus(), is(407));

            MockHttpServletResponse forwarded = send(get("/upstream", host, PASSWORD));
            assertThat(forwarded.getStatus(), is(200));
            HttpRequest received = upstream.getLastRequest();
            assertThat(received.getPath().getValue(), is("/upstream"));
            assertThat(received.containsHeader("Proxy-Authorization"), is(false));
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void shouldRequireProxyCredentialsToForwardAnUnmatchedRequestForItselfToProxyRemoteHost() throws Exception {
        try (ConnectionCounter remote = new ConnectionCounter()) {
            start(configuration()
                .proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD)
                .proxyRemoteHost("127.0.0.1").proxyRemotePort(remote.port()));

            MockHttpServletResponse refused = send(get("/unmatched", "localhost", null));
            assertThat(refused.getStatus(), is(407));
            assertThat("connections to proxyRemoteHost", remote.connected(), is(false));

            send(get("/unmatched", "localhost", PASSWORD));
            assertThat("connections to proxyRemoteHost", remote.connected(), is(true));
        }
    }

    private static MockHttpServletRequest get(String path, String host, String password) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Host", host);
        if (password != null) {
            request.addHeader("Proxy-Authorization", "Basic " + Base64.getEncoder().encodeToString((USERNAME + ":" + password).getBytes(UTF_8)));
        }
        return request;
    }

    private static MockHttpServletRequest on(int localPort, MockHttpServletRequest request) {
        request.setLocalAddr("127.0.0.1");
        request.setLocalPort(localPort);
        request.setServerPort(localPort);
        return request;
    }

    /**
     * Stands in for the servlet container's own port, or an upstream: records whether anything connected, and closes
     * each connection without answering.
     */
    private static final class ConnectionCounter implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        private final CountDownLatch connection = new CountDownLatch(1);

        ConnectionCounter() throws IOException {
            Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try (Socket ignored = serverSocket.accept()) {
                        connection.countDown();
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "connection-counter");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        boolean connected() throws InterruptedException {
            return connection.await(500, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}

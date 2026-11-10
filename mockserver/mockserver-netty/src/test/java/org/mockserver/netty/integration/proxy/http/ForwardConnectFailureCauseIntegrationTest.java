package org.mockserver.netty.integration.proxy.http;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.matchers.Times;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.ProxyPassMapping.proxyPass;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.ClosedPort.CLOSED_PORT;

/**
 * The {@code 502} a forward is answered with when its connection to the upstream could not be made or was lost: each
 * cause is named, with what to change, and the body names neither the upstream's host nor its address. Every failure
 * here is a real one: a closed port, a {@code .invalid} host name, an address nothing answers, a pooled connection the
 * upstream closes, an unreachable upstream proxy and a forward back to MockServer itself that times out.
 */
public class ForwardConnectFailureCauseIntegrationTest {

    // TEST-NET-1 (RFC 5737): never routed, so a connect to it is not answered
    private static final String UNANSWERED_ADDRESS = "192.0.2.1";
    private static final String UNRESOLVABLE_HOST = "no-such-upstream.invalid";

    private static KeepAliveThenClosingUpstream poolClosingUpstream;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServer throughUnreachableProxy;
    private static MockServerClient throughUnreachableProxyClient;
    private static MockServer impatient;
    private static MockServerClient impatientClient;

    @BeforeClass
    public static void startServers() throws Exception {
        poolClosingUpstream = new KeepAliveThenClosingUpstream();
        mockServer = new MockServer(forwarding()
            .socketConnectionTimeoutInMillis(250L)
            .proxyPassMappings(Collections.singletonList(proxyPass("/proxy-pass", "http://127.0.0.1:" + CLOSED_PORT))), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        throughUnreachableProxy = new MockServer(forwarding().forwardHttpProxy(new InetSocketAddress("127.0.0.1", CLOSED_PORT)), 0);
        throughUnreachableProxyClient = new MockServerClient("127.0.0.1", throughUnreachableProxy.getLocalPort());
        impatient = new MockServer(forwarding().maxSocketTimeoutInMillis(500L).maxFutureTimeoutInMillis(500L), 0);
        impatientClient = new MockServerClient("127.0.0.1", impatient.getLocalPort());
    }

    private static Configuration forwarding() {
        return configuration().forwardProxyBlockPrivateNetworks(false);
    }

    @AfterClass
    public static void stopServers() throws IOException {
        for (MockServerClient client : Arrays.asList(mockServerClient, throughUnreachableProxyClient, impatientClient)) {
            stopQuietly(client);
        }
        for (MockServer server : Arrays.asList(mockServer, throughUnreachableProxy, impatient)) {
            stopQuietly(server);
        }
        poolClosingUpstream.close();
    }

    @Before
    public void createExpectations() {
        for (MockServerClient client : Arrays.asList(mockServerClient, throughUnreachableProxyClient, impatientClient)) {
            client.reset();
        }
        mockServerClient.when(request().withPath("/refused")).forward(forward().withHost("127.0.0.1").withPort(CLOSED_PORT));
        mockServerClient.when(request().withPath("/unresolvable")).forward(forward().withHost(UNRESOLVABLE_HOST).withPort(80));
        mockServerClient.when(request().withPath("/unanswered")).forward(forward().withHost(UNANSWERED_ADDRESS).withPort(9));
        mockServerClient.when(request().withPath("/pooled/.*")).forward(forward().withHost("127.0.0.1").withPort(poolClosingUpstream.port()));
        throughUnreachableProxyClient.when(request().withPath("/through-proxy")).forward(forward().withHost("upstream.example").withPort(80));
    }

    @Test
    public void shouldNameARefusedConnection() throws Exception {
        Response response = send(mockServer, "GET", "/refused");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, is("upstream refused the connection: check the upstream is running and listening on the port forwarded to"));
        assertThat(response.body, not(containsString("127.0.0.1")));
    }

    @Test
    public void shouldNameAHostNameThatDoesNotResolve() throws Exception {
        Response response = send(mockServer, "GET", "/unresolvable");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, is("upstream host name could not be resolved: check it is spelled correctly and resolves where MockServer runs (its DNS, hosts file or container network)"));
        assertThat(response.body, not(containsString("no-such-upstream")));
    }

    @Test
    public void shouldNameAConnectionThatTimedOut() throws Exception {
        long started = System.nanoTime();

        Response response = send(mockServer, "GET", "/unanswered");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, is("connection to the upstream timed out: check the upstream is reachable from where MockServer runs (firewall, routing, VPN or an upstream proxy it must go through), or raise socketConnectionTimeoutInMillis if it is slow to accept connections"));
        assertThat(response.body, not(containsString(UNANSWERED_ADDRESS)));
        assertThat("the connect gave up after socketConnectionTimeoutInMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), lessThan(10_000L));
    }

    @Test
    public void shouldNameARefusedConnectionForARequestProxiedToItsHost() throws Exception {
        Response response = sendAbsolute(mockServer, "http://127.0.0.1:" + CLOSED_PORT + "/unmatched", "127.0.0.1:" + CLOSED_PORT);

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, is("upstream refused the connection: check the upstream is running and listening on the port forwarded to"));
    }

    @Test
    public void shouldNameARefusedConnectionForAProxyPassMapping() throws Exception {
        Response response = send(mockServer, "GET", "/proxy-pass/somewhere");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, is("upstream refused the connection: check the upstream is running and listening on the port forwarded to"));
    }

    @Test
    public void shouldNameAnUpstreamProxyThatCouldNotBeReached() throws Exception {
        Response response = send(throughUnreachableProxy, "GET", "/through-proxy");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, is("upstream proxy set by forwardHttpProxy could not be reached (it refused the connection): check the proxy is running and its host and port are correct"));
    }

    @Test
    public void shouldNameAPooledConnectionTheUpstreamClosedForARequestThatIsNotSentAgain() throws Exception {
        assertThat(send(mockServer, "GET", "/pooled/first").status, is(200));

        Response response = send(mockServer, "POST", "/pooled/second");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, is("upstream closed the pooled keep-alive connection the request was sent on without answering, and a POST is not sent again in case the upstream acted on it: if the upstream closes idle connections, set forwardConnectionPoolIdleTimeoutMillis below its keep-alive timeout or set forwardConnectionPoolEnabled to false; otherwise the upstream may have closed the connection to reject the request"));
        assertThat("one connection, reused", poolClosingUpstream.connections.get(), is(1));
    }

    @Test
    public void shouldHintAtALoopWhenAForwardBackToMockServerItselfFails() throws Exception {
        // the forwarded request matches a slow expectation, so the forward to MockServer itself fails on the
        // socket timeout rather than repeating until local ports run out
        impatientClient.when(request().withPath("/loop"), Times.exactly(1)).forward(forward().withHost("127.0.0.1").withPort(impatient.getLocalPort()));
        impatientClient.when(request().withPath("/loop")).respond(response().withDelay(TimeUnit.SECONDS, 3));

        Response response = send(impatient, "GET", "/loop");

        assertThat(response.toString(), response.status, is(502));
        assertThat(response.body, is("the forward's target is the address MockServer received the request on, so the request may have been forwarded back to MockServer itself, where it can match the same expectation again and loop: check the forward's host and port"));
        assertThat(response.body, not(containsString(String.valueOf(impatient.getLocalPort()))));
    }

    private static Response send(MockServer mockServer, String method, String path) throws IOException {
        return exchange(mockServer, method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + mockServer.getLocalPort() + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    }

    private static Response sendAbsolute(MockServer mockServer, String uri, String host) throws IOException {
        return exchange(mockServer, "GET " + uri + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n");
    }

    private static Response exchange(MockServer mockServer, String head) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
            socket.setSoTimeout(30_000);
            OutputStream output = socket.getOutputStream();
            output.write(head.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[16 * 1024];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                received.write(buffer, 0, read);
            }
            return new Response(received.toString(StandardCharsets.ISO_8859_1.name()));
        }
    }

    private static String readRequestHead(InputStream input) throws IOException {
        StringBuilder head = new StringBuilder();
        for (int read = input.read(); read != -1; read = input.read()) {
            head.append((char) read);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) {
                return head.toString();
            }
        }
        return null;
    }

    /**
     * Answers the first request on a connection, keeping it open, and closes the connection when the next request on it
     * arrives, without answering: what an upstream does that closes an idle keep-alive connection as it is reused.
     */
    private static final class KeepAliveThenClosingUpstream {
        private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        private final AtomicInteger connections = new AtomicInteger();

        private KeepAliveThenClosingUpstream() throws IOException {
            Thread acceptor = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        connections.incrementAndGet();
                        socket.setSoTimeout(30_000);
                        if (readRequestHead(socket.getInputStream()) != null) {
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\ncontent-length: 2\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1));
                            socket.getOutputStream().flush();
                            readRequestHead(socket.getInputStream());
                        }
                    } catch (IOException closedOrFailed) {
                        // the next connection, or the end of the loop
                    }
                }
            }, "keep-alive-then-closing-upstream");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private int port() {
            return listener.getLocalPort();
        }

        private void close() throws IOException {
            listener.close();
        }
    }

    private static final class Response {
        private final int status;
        private final String body;
        private final String raw;

        private Response(String raw) {
            this.raw = raw;
            this.status = raw.length() > 12 ? Integer.parseInt(raw.substring(9, 12)) : -1;
            int bodyStart = raw.indexOf("\r\n\r\n");
            this.body = bodyStart < 0 || bodyStart + 4 == raw.length() ? null : raw.substring(bodyStart + 4);
        }

        @Override
        public String toString() {
            return raw;
        }
    }
}

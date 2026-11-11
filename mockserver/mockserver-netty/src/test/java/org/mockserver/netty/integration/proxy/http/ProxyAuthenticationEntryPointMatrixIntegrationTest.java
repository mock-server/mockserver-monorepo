package org.mockserver.netty.integration.proxy.http;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.PortFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * With {@code proxyAuthenticationUsername} and {@code proxyAuthenticationPassword} set, each way of using MockServer as
 * a proxy, with no, wrong and right credentials. The rule under test:
 * <ul>
 *   <li>a proxy request for another host (absolute-form request-target with another {@code Host}, {@code CONNECT},
 *   SOCKS) without valid
 *   credentials gets {@code 407} (or the SOCKS refusal), is not matched against expectations, and nothing reaches the
 *   other host;</li>
 *   <li>an origin-form request (HTTP/1.1 path, HTTP/2 {@code :path}) is addressed to MockServer whatever its
 *   {@code Host} or {@code :authority} says, so it is matched against expectations as a mock without proxy
 *   credentials; only when nothing matches and MockServer would forward it to that other host does it need them;</li>
 *   <li>a request for MockServer itself never needs them.</li>
 * </ul>
 * Each cell asks for a path with an expectation on the proxy and a path without one, and checks what the upstream
 * received.
 */
@RunWith(Parameterized.class)
public class ProxyAuthenticationEntryPointMatrixIntegrationTest {

    private static final String USERNAME = "proxy-user";
    private static final String PASSWORD = "proxy-password";
    private static final String MOCKED_PATH = "/matrix/mocked";
    private static final String UNMATCHED_PATH = "/matrix/unmatched";
    // matched on its headers, before the body is read
    private static final String EARLY_MOCKED_PATH = "/matrix/early";
    private static final String MOCK_BODY = "from proxy expectation";
    private static final String UPSTREAM_BODY = "from upstream";
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private static ClientAndServer upstream;
    private static ClientAndServer proxy;
    private static int boundPort;
    private static EventLoopGroup clientEventLoopGroup;

    enum Credentials {
        NONE(null),
        WRONG(basic(USERNAME, "not-the-password")),
        RIGHT(basic(USERNAME, PASSWORD));

        final String header;

        Credentials(String header) {
            this.header = header;
        }
    }

    /**
     * What an entry point is expected to do without valid credentials.
     */
    enum Rule {
        /** a proxy request for another host: refused, nothing matched or forwarded */
        PROXY,
        /** origin-form with another Host: mocks served, an unmatched request is refused rather than forwarded */
        ORIGIN_FORM_OTHER_HOST,
        /** addressed to MockServer itself: credentials are irrelevant */
        SELF,
        /** SOCKS4 cannot carry a password, so it is refused even with the right user id */
        ALWAYS_REFUSED
    }

    enum EntryPoint {
        HTTP1_ABSOLUTE_FORM(Rule.PROXY) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                return http1(proxy.getLocalPort(), "GET http://" + upstreamAuthority() + path + " HTTP/1.1", upstreamAuthority(), credentials, "");
            }
        },
        HTTP1_ABSOLUTE_FORM_WITH_OWN_HOST_HEADER(Rule.SELF) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                return http1(proxy.getLocalPort(), "GET http://" + upstreamAuthority() + path + " HTTP/1.1", proxyAuthority(), credentials, "");
            }
        },
        HTTP1_ABSOLUTE_FORM_ON_BOUND_PORT(Rule.PROXY) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                return http1(boundPort, "GET http://" + upstreamAuthority() + path + " HTTP/1.1", upstreamAuthority(), credentials, "");
            }
        },
        HTTP1_ABSOLUTE_FORM_RESPOND_BEFORE_BODY(Rule.PROXY) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                String early = MOCKED_PATH.equals(path) ? EARLY_MOCKED_PATH : path;
                return http1(proxy.getLocalPort(), "POST http://" + upstreamAuthority() + early + " HTTP/1.1", upstreamAuthority(), credentials, "");
            }
        },
        HTTP1_ABSOLUTE_FORM_WEBSOCKET_UPGRADE(Rule.PROXY) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                return http1(proxy.getLocalPort(), "GET http://" + upstreamAuthority() + path + " HTTP/1.1", upstreamAuthority(), credentials, WEBSOCKET_UPGRADE_HEADERS);
            }
        },
        HTTP1_CONNECT(Rule.PROXY) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                try (Socket socket = connect(proxy.getLocalPort())) {
                    write(socket, "CONNECT " + upstreamAuthority() + " HTTP/1.1\r\nHost: " + upstreamAuthority() + "\r\n" + proxyAuthorization(credentials) + "\r\n");
                    Outcome connect = readResponse(socket);
                    if (connect.status != 200) {
                        return connect;
                    }
                    write(socket, "GET " + path + " HTTP/1.1\r\nHost: " + upstreamAuthority() + "\r\nContent-Length: 0\r\n\r\n");
                    return readResponse(socket);
                }
            }
        },
        HTTP2_CONNECT(Rule.PROXY) {
            @Override
            Outcome send(Credentials credentials, String path) throws Exception {
                HttpResponse connect = http2(request().withMethod("CONNECT").withPath(upstreamAuthority()), credentials);
                // a tunnel inside an HTTP/2 stream is not driven here: the cell checks only that it is refused
                return new Outcome(connect.getStatusCode(), connect.getBodyAsString());
            }
        },
        SOCKS5(Rule.PROXY) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                try (Socket socket = connect(proxy.getLocalPort())) {
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();
                    out.write(credentials == Credentials.NONE ? new byte[]{5, 1, 0} : new byte[]{5, 1, 2});
                    out.flush();
                    byte[] method = readBytes(in, 2);
                    if (method[1] != 2) {
                        return Outcome.refused("SOCKS5 method " + method[1]);
                    }
                    String password = credentials == Credentials.RIGHT ? PASSWORD : "not-the-password";
                    ByteArrayOutputStream auth = new ByteArrayOutputStream();
                    auth.write(1);
                    auth.write(USERNAME.length());
                    auth.write(USERNAME.getBytes(StandardCharsets.US_ASCII));
                    auth.write(password.length());
                    auth.write(password.getBytes(StandardCharsets.US_ASCII));
                    out.write(auth.toByteArray());
                    out.flush();
                    byte[] authReply = readBytes(in, 2);
                    if (authReply[1] != 0) {
                        return Outcome.refused("SOCKS5 authentication status " + authReply[1]);
                    }
                    int port = upstream.getLocalPort();
                    out.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
                    out.flush();
                    byte[] reply = readBytes(in, 4);
                    if (reply[1] != 0) {
                        return Outcome.refused("SOCKS5 CONNECT status " + reply[1]);
                    }
                    int addressLength = reply[3] == 1 ? 4 : reply[3] == 4 ? 16 : readBytes(in, 1)[0] & 0xff;
                    readBytes(in, addressLength + 2);
                    write(socket, "GET " + path + " HTTP/1.1\r\nHost: " + upstreamAuthority() + "\r\nContent-Length: 0\r\n\r\n");
                    return readResponse(socket);
                }
            }
        },
        SOCKS4(Rule.ALWAYS_REFUSED) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                try (Socket socket = connect(proxy.getLocalPort())) {
                    int port = upstream.getLocalPort();
                    ByteArrayOutputStream request = new ByteArrayOutputStream();
                    request.write(new byte[]{4, 1, (byte) (port >> 8), (byte) port, 127, 0, 0, 1});
                    if (credentials != Credentials.NONE) {
                        request.write(USERNAME.getBytes(StandardCharsets.US_ASCII));
                    }
                    request.write(0);
                    socket.getOutputStream().write(request.toByteArray());
                    socket.getOutputStream().flush();
                    byte[] reply = readBytes(socket.getInputStream(), 8);
                    if (reply[1] != 0x5a) {
                        return Outcome.refused("SOCKS4 status " + reply[1]);
                    }
                    write(socket, "GET " + path + " HTTP/1.1\r\nHost: " + upstreamAuthority() + "\r\nContent-Length: 0\r\n\r\n");
                    return readResponse(socket);
                }
            }
        },
        HTTP1_ORIGIN_FORM_OTHER_HOST(Rule.ORIGIN_FORM_OTHER_HOST) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                return http1(proxy.getLocalPort(), "GET " + path + " HTTP/1.1", upstreamAuthority(), credentials, "");
            }
        },
        HTTP1_ABSOLUTE_FORM_FOR_ITSELF_WITH_OTHER_HOST_HEADER(Rule.PROXY) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                return http1(proxy.getLocalPort(), "GET http://" + proxyAuthority() + path + " HTTP/1.1", upstreamAuthority(), credentials, "");
            }
        },
        HTTP1_ORIGIN_FORM_OTHER_HOST_WEBSOCKET_UPGRADE(Rule.ORIGIN_FORM_OTHER_HOST) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                return http1(proxy.getLocalPort(), "GET " + path + " HTTP/1.1", upstreamAuthority(), credentials, WEBSOCKET_UPGRADE_HEADERS);
            }
        },
        HTTP2_OTHER_AUTHORITY(Rule.ORIGIN_FORM_OTHER_HOST) {
            @Override
            Outcome send(Credentials credentials, String path) throws Exception {
                HttpResponse response = http2(request().withMethod("GET").withPath(path).withHeader(HOST.toString(), upstreamAuthority()), credentials);
                return new Outcome(response.getStatusCode(), response.getBodyAsString());
            }
        },
        HTTP1_ORIGIN_FORM_SELF(Rule.SELF) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                return http1(proxy.getLocalPort(), "GET " + path + " HTTP/1.1", proxyAuthority(), credentials, "");
            }
        },
        HTTP1_ABSOLUTE_FORM_SELF(Rule.SELF) {
            @Override
            Outcome send(Credentials credentials, String path) throws IOException {
                return http1(proxy.getLocalPort(), "GET http://" + proxyAuthority() + path + " HTTP/1.1", proxyAuthority(), credentials, "");
            }
        },
        HTTP2_SELF(Rule.SELF) {
            @Override
            Outcome send(Credentials credentials, String path) throws Exception {
                HttpResponse response = http2(request().withMethod("GET").withPath(path).withHeader(HOST.toString(), proxyAuthority()), credentials);
                return new Outcome(response.getStatusCode(), response.getBodyAsString());
            }
        };

        final Rule rule;

        EntryPoint(Rule rule) {
            this.rule = rule;
        }

        abstract Outcome send(Credentials credentials, String path) throws Exception;
    }

    private static final String WEBSOCKET_UPGRADE_HEADERS = "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
        "Sec-WebSocket-Version: 13\r\n";

    @Parameterized.Parameters(name = "{0} {1}")
    public static Collection<Object[]> cells() {
        List<Object[]> cells = new ArrayList<>();
        for (EntryPoint entryPoint : EntryPoint.values()) {
            for (Credentials credentials : Credentials.values()) {
                cells.add(new Object[]{entryPoint, credentials});
            }
        }
        return cells;
    }

    private final EntryPoint entryPoint;
    private final Credentials credentials;

    public ProxyAuthenticationEntryPointMatrixIntegrationTest(EntryPoint entryPoint, Credentials credentials) {
        this.entryPoint = entryPoint;
        this.credentials = credentials;
    }

    @BeforeClass
    public static void startServers() {
        clientEventLoopGroup = new NioEventLoopGroup(2, new Scheduler.SchedulerThreadFactory(ProxyAuthenticationEntryPointMatrixIntegrationTest.class.getSimpleName() + "-eventLoop"));
        upstream = startClientAndServer(configuration());
        proxy = startClientAndServer(configuration()
            .proxyAuthenticationUsername(USERNAME)
            .proxyAuthenticationPassword(PASSWORD));
        boundPort = proxy.bind(PortFactory.findFreePort()).get(0);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(proxy);
        stopQuietly(upstream);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    @Before
    public void resetServers() {
        upstream.reset();
        upstream.when(request().withPath("/matrix/.*")).respond(response().withStatusCode(200).withBody(UPSTREAM_BODY));
        proxy.reset();
        proxy.when(request().withPath(MOCKED_PATH), Times.once()).respond(response().withStatusCode(200).withBody(MOCK_BODY));
        proxy.when(request().withPath(EARLY_MOCKED_PATH).withRespondBeforeBody(true)).respond(response().withStatusCode(200).withBody(MOCK_BODY));
    }

    @Test
    public void requestForAPathWithAnExpectation() throws Exception {
        Outcome outcome = entryPoint.send(credentials, MOCKED_PATH);

        if (refusedWithout(credentials)) {
            assertRefused(outcome);
            assertThat("not matched: " + outcome, outcome.body, not(MOCK_BODY));
            assertThat("Times not used up", proxy.retrieveActiveExpectations(request().withPath(MOCKED_PATH)).length, is(1));
            if (entryPoint.rule == Rule.PROXY && entryPoint.name().startsWith("HTTP1_ABSOLUTE_FORM")) {
                assertThat("logged as a failed proxy authentication, not as received", proxy.retrieveRecordedRequests(request().withPath("/matrix/.*")).length, is(0));
            }
        } else if (entryPoint == EntryPoint.HTTP2_CONNECT) {
            assertThat(outcome.toString(), outcome.status, is(200));
        } else {
            assertThat(outcome.toString(), outcome.status, is(200));
            assertThat(outcome.toString(), outcome.body, is(MOCK_BODY));
        }
        assertUpstreamReceived(0);
    }

    @Test
    public void requestForAPathWithoutAnExpectation() throws Exception {
        Outcome outcome = entryPoint.send(credentials, UNMATCHED_PATH);

        if (refusedWithout(credentials) || entryPoint.rule == Rule.ORIGIN_FORM_OTHER_HOST && credentials != Credentials.RIGHT) {
            assertRefused(outcome);
            assertUpstreamReceived(0);
        } else if (entryPoint.rule == Rule.SELF) {
            assertThat("served by MockServer itself: " + outcome, outcome.status, is(404));
            assertUpstreamReceived(0);
        } else if (entryPoint == EntryPoint.HTTP2_CONNECT) {
            assertThat(outcome.toString(), outcome.status, is(200));
        } else if (entryPoint == EntryPoint.HTTP1_ABSOLUTE_FORM_WEBSOCKET_UPGRADE || entryPoint == EntryPoint.HTTP1_ORIGIN_FORM_OTHER_HOST_WEBSOCKET_UPGRADE) {
            // the upstream answers the handshake 404, which the relay reports; what matters is that it was relayed
            assertUpstreamReceived(1);
        } else {
            assertThat(outcome.toString(), outcome.status, is(200));
            assertThat(outcome.toString(), outcome.body, is(UPSTREAM_BODY));
            assertUpstreamReceived(1);
        }
    }

    private boolean refusedWithout(Credentials credentials) {
        return entryPoint.rule == Rule.ALWAYS_REFUSED || entryPoint.rule == Rule.PROXY && credentials != Credentials.RIGHT;
    }

    private void assertRefused(Outcome outcome) {
        if (entryPoint == EntryPoint.SOCKS5 || entryPoint == EntryPoint.SOCKS4) {
            assertThat("SOCKS refusal: " + outcome, outcome.status, is(Outcome.SOCKS_REFUSED));
        } else {
            assertThat("407: " + outcome, outcome.status, is(407));
        }
    }

    private static void assertUpstreamReceived(int count) {
        HttpRequest[] received = upstream.retrieveRecordedRequests(request().withPath("/matrix/.*"));
        assertThat("requests the upstream received", received.length, is(count));
    }

    static final class Outcome {
        static final int SOCKS_REFUSED = -1;

        final int status;
        final String body;

        Outcome(int status, String body) {
            this.status = status;
            this.body = body;
        }

        static Outcome refused(String reason) {
            return new Outcome(SOCKS_REFUSED, reason);
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String upstreamAuthority() {
        return "127.0.0.1:" + upstream.getLocalPort();
    }

    private static String proxyAuthority() {
        return "127.0.0.1:" + proxy.getLocalPort();
    }

    private static String proxyAuthorization(Credentials credentials) {
        return credentials.header == null ? "" : PROXY_AUTHORIZATION + ": " + credentials.header + "\r\n";
    }

    private static Outcome http1(int port, String requestLine, String host, Credentials credentials, String extraHeaders) throws IOException {
        try (Socket socket = connect(port)) {
            write(socket, requestLine + "\r\nHost: " + host + "\r\n" + proxyAuthorization(credentials) + extraHeaders + "Content-Length: 0\r\n\r\n");
            return readResponse(socket);
        }
    }

    private static HttpResponse http2(HttpRequest request, Credentials credentials) throws Exception {
        if (credentials.header != null) {
            request.withHeader(PROXY_AUTHORIZATION.toString(), credentials.header);
        }
        return new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
            .sendRequest(request.withSecure(true).withProtocol(Protocol.HTTP_2), new InetSocketAddress("127.0.0.1", proxy.getLocalPort()))
            .get(READ_TIMEOUT_MILLIS, MILLISECONDS);
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), READ_TIMEOUT_MILLIS);
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
                if (read == 0) {
                    // closed without an answer: report it as a refusal rather than a failure to read
                    bytes[1] = (byte) 0xff;
                    return bytes;
                }
                throw new IOException("closed after " + read + " of " + length + " bytes");
            }
            read += n;
        }
        return bytes;
    }

    /** The status and body of the next response, read by its Content-Length. */
    private static Outcome readResponse(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        try {
            while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                int b = in.read();
                if (b == -1) {
                    break;
                }
                head.write(b);
            }
        } catch (SocketException reset) {
            // reset before or during the head: whatever arrived is the answer
        }
        String text = head.toString(StandardCharsets.ISO_8859_1);
        if (!text.startsWith("HTTP/1.1 ")) {
            return new Outcome(0, "no HTTP response: " + text);
        }
        int contentLength = 0;
        for (String line : text.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        byte[] body = new byte[contentLength];
        for (int read = 0; read < contentLength; ) {
            int n = in.read(body, read, contentLength - read);
            if (n == -1) {
                break;
            }
            read += n;
        }
        return new Outcome(Integer.parseInt(text.substring(9, 12)), new String(body, StandardCharsets.UTF_8));
    }
}

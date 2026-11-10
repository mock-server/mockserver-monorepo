package org.mockserver.httpclient;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.proxy.ProxyConnectException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.slf4j.event.Level;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;
import static org.mockserver.test.ClosedPort.CLOSED_PORT;

/**
 * A forward that fails because its configured upstream proxy could not be reached (its name does not resolve, or it
 * refuses or does not answer the connection) fails with {@link UpstreamProxyUnreachableException}, which reads as its
 * cause; a failure reported by a proxy that was reached, and a failure to reach a target directly, do not.
 */
public class NettyHttpClientUpstreamProxyFailureTest {

    // RFC 6761: names under .invalid never resolve
    private static final String UNRESOLVABLE_PROXY = "upstream-proxy-for-mockserver.invalid";
    private static final long TIMEOUT_SECONDS = 20;

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private final MockServerLogger mockServerLogger = new MockServerLogger(NettyHttpClientUpstreamProxyFailureTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };
    private EventLoopGroup group;
    private final List<ServerSocket> servers = new CopyOnWriteArrayList<>();

    @Before
    public void startGroup() {
        group = new NioEventLoopGroup(2);
    }

    @After
    public void stopGroupAndServers() throws IOException {
        for (ServerSocket server : servers) {
            server.close();
        }
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    private static Configuration forwardConfiguration() {
        return configuration()
            .socketConnectionTimeoutInMillis(5_000L)
            .forwardConnectionPoolEnabled(false);
    }

    private NettyHttpClient forwardClientThrough(ProxyConfiguration.Type type, InetSocketAddress proxy) {
        return new NettyHttpClient(forwardConfiguration(), mockServerLogger, group, Collections.singletonList(proxyConfiguration(type, proxy)), true);
    }

    private static InetSocketAddress unresolvableProxy() {
        InetSocketAddress proxy = new InetSocketAddress(UNRESOLVABLE_PROXY, 3128);
        assertThat("this test needs a proxy name this JVM cannot resolve", proxy.isUnresolved(), is(true));
        return proxy;
    }

    private static InetSocketAddress refusingProxy() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), CLOSED_PORT);
    }

    private static HttpRequest plainRequest() {
        return request("/through-proxy").withHeader("Host", "target.example:8080");
    }

    private static HttpRequest secureRequest() {
        return request("/through-proxy").withSecure(true).withHeader("Host", "target.example:8443");
    }

    private static Throwable failureOf(CompletableFuture<HttpResponse> sent) {
        ExecutionException failed = assertThrows(ExecutionException.class, () -> sent.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return failed.getCause();
    }

    private static void assertUnreachable(Throwable failure, InetSocketAddress proxy, Class<? extends Throwable> causeType) {
        UpstreamProxyUnreachableException unreachable = UpstreamProxyUnreachableException.in(failure);
        assertThat("failure " + failure + " is reported as the upstream proxy being unreachable", unreachable, is(notNullValue()));
        assertThat(unreachable.getProxyAddress(), is(proxy));
        assertThat(causeType.isInstance(unreachable.getCause()), is(true));
        // it reads as its cause, so what a client is answered and what is logged for the forward are unchanged
        assertThat(unreachable.toString(), is(unreachable.getCause().toString()));
        assertThat(unreachable.getMessage(), is(unreachable.getCause().getMessage()));
    }

    private static void assertNotUnreachable(Throwable failure) {
        assertThat("failure " + failure + " is not reported as the upstream proxy being unreachable", UpstreamProxyUnreachableException.in(failure), is(nullValue()));
    }

    @Test
    public void shouldReportAForwardHttpProxyWhoseNameDoesNotResolveAsUnreachable() {
        InetSocketAddress proxy = unresolvableProxy();

        Throwable failure = failureOf(forwardClientThrough(ProxyConfiguration.Type.HTTP, proxy).sendRequest(plainRequest(), null));

        assertUnreachable(failure, proxy, UnknownHostException.class);
    }

    @Test
    public void shouldReportAForwardHttpProxyThatRefusesTheConnectionAsUnreachable() throws IOException {
        InetSocketAddress proxy = refusingProxy();

        Throwable failure = failureOf(forwardClientThrough(ProxyConfiguration.Type.HTTP, proxy).sendRequest(plainRequest(), null));

        assertUnreachable(failure, proxy, java.net.ConnectException.class);
    }

    @Test
    public void shouldReportAForwardHttpsProxyWhoseNameDoesNotResolveAsUnreachable() {
        InetSocketAddress proxy = unresolvableProxy();

        Throwable failure = failureOf(forwardClientThrough(ProxyConfiguration.Type.HTTPS, proxy).sendRequest(secureRequest(), null));

        assertUnreachable(failure, proxy, java.nio.channels.UnresolvedAddressException.class);
    }

    @Test
    public void shouldReportAForwardHttpsProxyThatRefusesTheConnectionAsUnreachable() throws IOException {
        InetSocketAddress proxy = refusingProxy();

        Throwable failure = failureOf(forwardClientThrough(ProxyConfiguration.Type.HTTPS, proxy).sendRequest(secureRequest(), null));

        assertUnreachable(failure, proxy, java.net.ConnectException.class);
    }

    @Test
    public void shouldReportAForwardSocksProxyWhoseNameDoesNotResolveAsUnreachable() {
        InetSocketAddress proxy = unresolvableProxy();

        Throwable failure = failureOf(forwardClientThrough(ProxyConfiguration.Type.SOCKS5, proxy).sendRequest(plainRequest(), null));

        assertUnreachable(failure, proxy, java.nio.channels.UnresolvedAddressException.class);
    }

    @Test
    public void shouldReportAForwardSocksProxyThatRefusesTheConnectionAsUnreachable() throws IOException {
        InetSocketAddress proxy = refusingProxy();

        Throwable failure = failureOf(forwardClientThrough(ProxyConfiguration.Type.SOCKS5, proxy).sendRequest(secureRequest(), null));

        assertUnreachable(failure, proxy, java.net.ConnectException.class);
    }

    @Test
    public void shouldNotReportATargetAWorkingConnectProxyCouldNotReachAsTheProxyBeingUnreachable() throws Exception {
        InetSocketAddress proxy = proxyAnswering(connection -> {
            readHead(connection);
            write(connection, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        });

        Throwable failure = failureOf(forwardClientThrough(ProxyConfiguration.Type.HTTPS, proxy).sendRequest(secureRequest(), null));

        assertNotUnreachable(failure);
        assertThat(org.apache.commons.lang3.exception.ExceptionUtils.throwableOfType(failure, ProxyConnectException.class), is(notNullValue()));
    }

    @Test
    public void shouldNotReportATargetAWorkingSocksProxyCouldNotReachAsTheProxyBeingUnreachable() throws Exception {
        InetSocketAddress proxy = proxyAnswering(connection -> {
            InputStream in = connection.getInputStream();
            // greeting: version, method count, methods
            in.read();
            in.readNBytes(in.read());
            write(connection, new byte[]{5, 0});
            // request: version, command, reserved, then a domain name and port
            in.readNBytes(4);
            in.readNBytes(in.read() + 2);
            // general failure to connect to the target
            write(connection, new byte[]{5, 5, 0, 1, 0, 0, 0, 0, 0, 0});
        });

        Throwable failure = failureOf(forwardClientThrough(ProxyConfiguration.Type.SOCKS5, proxy).sendRequest(plainRequest(), null));

        assertNotUnreachable(failure);
        assertThat(org.apache.commons.lang3.exception.ExceptionUtils.throwableOfType(failure, ProxyConnectException.class), is(notNullValue()));
    }

    @Test
    public void shouldNotReportAProxyThatClosesTheTunnelBeforeAnsweringAsUnreachable() throws Exception {
        // some proxies close the connection rather than answer when the target cannot be reached
        InetSocketAddress proxy = proxyAnswering(NettyHttpClientUpstreamProxyFailureTest::readHead);

        Throwable failure = failureOf(forwardClientThrough(ProxyConfiguration.Type.HTTPS, proxy).sendRequest(secureRequest(), null));

        assertNotUnreachable(failure);
    }

    @Test
    public void shouldNotReportATargetThatRefusesADirectConnectionAsAProxyBeingUnreachable() throws IOException {
        InetSocketAddress target = refusingProxy();
        NettyHttpClient direct = new NettyHttpClient(forwardConfiguration(), mockServerLogger, group, null, true);

        Throwable failure = failureOf(direct.sendRequest(plainRequest(), target));

        assertNotUnreachable(failure);
    }

    @Test
    public void shouldNotReportASecureTargetSentDirectlyAsAForwardHttpProxyBeingUnreachable() throws IOException {
        // forwardHttpProxy carries only plain requests, so a secure one goes to its target directly
        InetSocketAddress target = refusingProxy();

        Throwable failure = failureOf(forwardClientThrough(ProxyConfiguration.Type.HTTP, unresolvableProxy()).sendRequest(secureRequest(), target));

        assertNotUnreachable(failure);
    }

    @Test
    public void shouldLeaveTheFailureAsItWasForAClientThatIsNotTheForwardClient() {
        NettyHttpClient notForwarding = new NettyHttpClient(forwardConfiguration(), mockServerLogger, group, Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.HTTP, unresolvableProxy())), false);

        Throwable failure = failureOf(notForwarding.sendRequest(plainRequest(), null));

        assertNotUnreachable(failure);
        assertThat(failure instanceof UnknownHostException, is(true));
    }

    @Test
    public void shouldThrowTheSameExceptionAsBeforeFromABlockingSend() throws IOException {
        NettyHttpClient unresolvable = forwardClientThrough(ProxyConfiguration.Type.HTTP, unresolvableProxy());
        SocketConnectionException notResolved = assertThrows(SocketConnectionException.class, () -> unresolvable.sendRequest(plainRequest(), TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(notResolved.getMessage(), containsString("Unable to resolve host"));
        assertThat(notResolved.getCause() instanceof UnknownHostException, is(true));

        NettyHttpClient refusing = forwardClientThrough(ProxyConfiguration.Type.HTTP, refusingProxy());
        SocketConnectionException notConnected = assertThrows(SocketConnectionException.class, () -> refusing.sendRequest(plainRequest(), TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(notConnected.getMessage(), containsString("Unable to connect to socket"));
    }

    @Test
    public void shouldLogAnUnreachableProxyOnceAsAProxyConfigurationProblem() throws IOException {
        InetSocketAddress unresolvable = unresolvableProxy();
        NettyHttpClient client = forwardClientThrough(ProxyConfiguration.Type.HTTPS, unresolvable);

        for (int i = 0; i < 3; i++) {
            failureOf(client.sendRequest(secureRequest(), null));
        }

        List<LogEntry> reports = proxyReports();
        assertThat(reports, hasSize(1));
        assertThat(reports.get(0).getLogLevel(), is(Level.WARN));
        assertThat(reports.get(0).getMessage(), containsString(UNRESOLVABLE_PROXY + ":3128"));
        assertThat(reports.get(0).getMessage(), containsString("forwardHttpsProxy"));

        // and - another proxy address is reported once too
        logged.clear();
        NettyHttpClient other = forwardClientThrough(ProxyConfiguration.Type.SOCKS5, refusingProxy());
        failureOf(other.sendRequest(plainRequest(), null));
        failureOf(other.sendRequest(plainRequest(), null));
        assertThat(proxyReports(), hasSize(1));
        assertThat(proxyReports().get(0).getMessage(), containsString("forwardSocksProxy"));
    }

    private List<LogEntry> proxyReports() {
        return logged.stream()
            .filter(entry -> entry.getMessageFormat() != null && entry.getMessageFormat().contains("could not be reached"))
            .collect(Collectors.toList());
    }

    private interface Answer {
        void accept(Socket connection) throws IOException;
    }

    private InetSocketAddress proxyAnswering(Answer answer) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        servers.add(server);
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket connection = server.accept()) {
                    connection.setSoTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                    answer.accept(connection);
                } catch (IOException closedOrFailed) {
                    // the next connection, if any, is answered afresh
                }
            }
        }, NettyHttpClientUpstreamProxyFailureTest.class.getSimpleName() + "-proxy");
        acceptor.setDaemon(true);
        acceptor.start();
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
    }

    private static void readHead(Socket connection) throws IOException {
        InputStream in = connection.getInputStream();
        int matched = 0;
        byte[] end = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        for (int read = in.read(); read != -1; read = in.read()) {
            matched = read == end[matched] ? matched + 1 : (read == end[0] ? 1 : 0);
            if (matched == end.length) {
                return;
            }
        }
    }

    private static void write(Socket connection, byte[] bytes) throws IOException {
        OutputStream out = connection.getOutputStream();
        out.write(bytes);
        out.flush();
    }
}

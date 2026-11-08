package org.mockserver.httpclient;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.proxyconfiguration.MalformedForwardTargetException;
import org.mockserver.proxyconfiguration.ProxyConfiguration;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

/**
 * A request whose destination an upstream proxy could read as a host other than the one MockServer read fails with
 * {@link MalformedForwardTargetException} before any connection is made to the proxy, with no forward-target check
 * configured: the absolute-form authority sent to forwardHttpProxy, and the tunnel destination sent to
 * forwardHttpsProxy and forwardSocksProxy. A well-formed destination is sent to each proxy as it was given.
 */
public class NettyHttpClientMalformedForwardTargetTest {

    private EventLoopGroup group;
    private ServerSocket proxy;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

    @Before
    public void startGroupAndRecordingProxy() throws IOException {
        group = new NioEventLoopGroup(1);
        proxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::recordConnections, "recording-proxy");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void stop() throws IOException {
        proxy.close();
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    private void recordConnections() {
        while (!proxy.isClosed()) {
            try (Socket connection = proxy.accept()) {
                connection.setSoTimeout(5_000);
                PushbackInputStream in = new PushbackInputStream(connection.getInputStream());
                int first = in.read();
                if (first == 5) {
                    received.add(socks5Destination(new DataInputStream(in), connection));
                    continue;
                }
                if (first != -1) {
                    in.unread(first);
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
                String firstLine = reader.readLine();
                received.add(firstLine != null ? firstLine : "(connected)");
                String line = firstLine;
                while (line != null && !line.isEmpty()) {
                    line = reader.readLine();
                }
                connection.getOutputStream().write("HTTP/1.1 200 OK\r\ncontent-length: 0\r\nconnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } catch (IOException closedOrFailed) {
                if (!proxy.isClosed()) {
                    received.add("(connected)");
                }
            }
        }
    }

    private static String socks5Destination(DataInputStream in, Socket connection) throws IOException {
        in.readFully(new byte[in.readUnsignedByte()]);
        connection.getOutputStream().write(new byte[]{5, 0});
        byte[] request = new byte[4];
        in.readFully(request);
        if (request[3] != 3) {
            return "SOCKS5 address type " + request[3];
        }
        byte[] host = new byte[in.readUnsignedByte()];
        in.readFully(host);
        return "SOCKS5 " + new String(host, StandardCharsets.ISO_8859_1) + ":" + in.readUnsignedShort();
    }

    private NettyHttpClient clientThrough(ProxyConfiguration.Type type) {
        return new NettyHttpClient(
            configuration().forwardConnectionPoolEnabled(false).socketConnectionTimeoutInMillis(5_000L),
            new MockServerLogger(), group,
            Collections.singletonList(proxyConfiguration(type, new InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.getLocalPort()))),
            true
        );
    }

    private void assertRefusedWithoutConnecting(CompletableFuture<HttpResponse> sent) throws InterruptedException {
        assertThat("refused before connecting", sent.isCompletedExceptionally(), is(true));
        ExecutionException failed = assertThrows(ExecutionException.class, () -> sent.get(1, TimeUnit.SECONDS));
        assertThat(failed.getCause(), instanceOf(MalformedForwardTargetException.class));
        assertThat("the proxy got no connection", received.poll(200, TimeUnit.MILLISECONDS), is(nullValue()));
    }

    @Test
    public void shouldNotSendForwardHttpProxyARequestWhoseHostIsNotOneHostOrHostAndPort() throws Exception {
        NettyHttpClient client = clientThrough(ProxyConfiguration.Type.HTTP);
        for (String host : new String[]{"8.8.8.8:80@169.254.169.254", "169.254.169.254?.8.8.8.8.nip.io", "0177.0.0.1", "0x7f.1", "example.com:0"}) {
            assertRefusedWithoutConnecting(client.sendRequest(request("/latest/meta-data/").withHeader("Host", host), null));
        }
        assertRefusedWithoutConnecting(client.sendRequest(request("/latest/meta-data/").withHeader("Host", "8.8.8.8", "169.254.169.254"), null));
    }

    @Test
    public void shouldNotOpenATunnelThroughForwardHttpsProxyToADestinationThatIsNotOneHost() throws Exception {
        NettyHttpClient client = clientThrough(ProxyConfiguration.Type.HTTPS);
        for (String host : new String[]{"secret.internal.example?.allowed.com", "secret.internal.example/.allowed.com", "user@secret.internal.example", "0177.0.0.1", "api.allowed.com:443:443"}) {
            assertRefusedWithoutConnecting(client.sendRequest(request("/x").withSecure(true).withHeader("Host", host), null));
        }
        assertRefusedWithoutConnecting(client.sendRequest(request("/x").withSecure(true), InetSocketAddress.createUnresolved("secret.internal.example#.allowed.com", 443)));
    }

    @Test
    public void shouldNotOpenATunnelThroughForwardSocksProxyToADestinationThatIsNotOneHost() throws Exception {
        NettyHttpClient client = clientThrough(ProxyConfiguration.Type.SOCKS5);
        for (String host : new String[]{"secret.internal.example?.allowed.com", "secret.internal.example#.allowed.com", "012.0.0.1"}) {
            assertRefusedWithoutConnecting(client.sendRequest(request("/x").withHeader("Host", host), null));
        }
    }

    @Test
    public void shouldOpenATunnelThroughForwardHttpsProxyToAWellFormedDestination() throws Exception {
        NettyHttpClient client = clientThrough(ProxyConfiguration.Type.HTTPS);

        client.sendRequest(request("/x").withSecure(true).withHeader("Host", "api.example.com:8443"), null);

        assertThat(received.poll(10, TimeUnit.SECONDS), is("CONNECT api.example.com:8443 HTTP/1.1"));
    }

    @Test
    public void shouldOpenATunnelThroughForwardSocksProxyToAWellFormedDestination() throws Exception {
        NettyHttpClient client = clientThrough(ProxyConfiguration.Type.SOCKS5);

        client.sendRequest(request("/x").withHeader("Host", "api.example.com:8080"), null);

        assertThat(received.poll(10, TimeUnit.SECONDS), is("SOCKS5 api.example.com:8080"));
    }

    @Test
    public void shouldSendForwardHttpProxyAWellFormedHostAsTheRequestLineAuthority() throws Exception {
        NettyHttpClient client = clientThrough(ProxyConfiguration.Type.HTTP);

        CompletableFuture<HttpResponse> sent = client.sendRequest(request("/x").withHeader("Host", "api.example.com:8080"), null);

        assertThat(received.poll(10, TimeUnit.SECONDS), is("GET http://api.example.com:8080/x HTTP/1.1"));
        assertThat(sent.get(10, TimeUnit.SECONDS).getStatusCode(), is(200));
    }
}

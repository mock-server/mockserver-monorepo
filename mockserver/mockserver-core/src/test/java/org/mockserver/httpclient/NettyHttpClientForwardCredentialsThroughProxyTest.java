package org.mockserver.httpclient;

import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Parameters;
import org.mockserver.model.SocketAddress;
import org.mockserver.proxyconfiguration.MalformedForwardTargetException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.Type.HTTP;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

/**
 * A plain HTTP request through {@code forwardHttpProxy} is delivered to the host its {@code Host} header names, not to
 * the address it was matched by, so a credential is added only when the two agree: otherwise a client that opened a
 * {@code CONNECT} to the configured host, or an override that set its socket address, could send it elsewhere.
 */
public class NettyHttpClientForwardCredentialsThroughProxyTest {

    // PATH is set in every environment the build runs in, so the value resolves without a test seam
    private static final String CREDENTIAL = System.getenv("PATH");

    private NioEventLoopGroup group;
    private ServerSocket fakeProxy;
    private final CompletableFuture<String> proxyReceived = new CompletableFuture<>();

    @Before
    public void startFakeProxy() throws IOException {
        group = new NioEventLoopGroup(1);
        fakeProxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            try (Socket socket = fakeProxy.accept()) {
                socket.setSoTimeout(10_000);
                proxyReceived.complete(readHead(socket.getInputStream()));
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\ncontent-length: 0\r\nconnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                proxyReceived.completeExceptionally(e);
            }
        }, "forward-credentials-fake-proxy");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void stop() throws IOException {
        fakeProxy.close();
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    private String sendThroughProxy(HttpRequest request, InetSocketAddress remoteAddress) throws Exception {
        return sendThroughProxy(configuration().forwardCredentialHeadersByHost("http://api.example.com:80=X-Credential:PATH"), request, remoteAddress).toLowerCase(Locale.ROOT);
    }

    private String sendThroughProxy(Configuration configuration, HttpRequest request, InetSocketAddress remoteAddress) throws Exception {
        NettyHttpClient client = new NettyHttpClient(configuration, new MockServerLogger(), group, List.of(proxyConfiguration(HTTP, "127.0.0.1:" + fakeProxy.getLocalPort())), true);
        client.sendRequest(request, remoteAddress).get(30, TimeUnit.SECONDS);
        return proxyReceived.get(30, TimeUnit.SECONDS);
    }

    @Test
    public void shouldSendTheCredentialWhenTheHostHeaderNamesTheConfiguredHost() throws Exception {
        String received = sendThroughProxy(request("/collect").withHeader("Host", "api.example.com"), InetSocketAddress.createUnresolved("api.example.com", 80));

        assertThat(received, containsString("get http://api.example.com/collect"));
        assertThat(received, containsString("x-credential: " + CREDENTIAL.toLowerCase(Locale.ROOT)));
    }

    @Test
    public void shouldNotSendTheCredentialWhenATunnelTargetIsTheConfiguredHostButTheHostHeaderIsNot() throws Exception {
        // as the unmatched proxy path sends a request after CONNECT api.example.com:80 that names another Host
        String received = sendThroughProxy(request("/collect").withHeader("Host", "attacker.example"), InetSocketAddress.createUnresolved("api.example.com", 80));

        assertThat(received, containsString("get http://attacker.example/collect"));
        assertThat(received, not(containsString("x-credential")));
    }

    @Test
    public void shouldNotSendTheCredentialWhenAnOverriddenSocketAddressIsTheConfiguredHostButTheHostHeaderIsNot() throws Exception {
        HttpRequest overridden = request("/collect")
            .withHeader("Host", "attacker.example")
            .withSocketAddress("api.example.com", 80, SocketAddress.Scheme.HTTP);

        String received = sendThroughProxy(overridden, null);

        assertThat(received, containsString("get http://attacker.example/collect"));
        assertThat(received, not(containsString("x-credential")));
    }

    @Test
    public void shouldNotSendTheCredentialWhenThePathWouldExtendTheAuthority() throws Exception {
        String received = sendThroughProxy(request(".evil.example/collect").withHeader("Host", "api.example.com"), InetSocketAddress.createUnresolved("api.example.com", 80));

        assertThat(received, startsWith("get http://api.example.com/.evil.example/collect "));
        assertThat(received, not(containsString("x-credential")));
    }

    @Test
    public void shouldNotSendTheCredentialWhenThePathSmugglesASchemeWithAPort() throws Exception {
        String received = sendThroughProxy(request("x.evil.example://api.example.com:80/collect").withHeader("Host", "api.example.com"), InetSocketAddress.createUnresolved("api.example.com", 80));

        assertThat(received, startsWith("get http://api.example.com/x.evil.example://api.example.com:80/collect "));
        assertThat(received, not(containsString("x-credential")));
    }

    @Test
    public void shouldNotSendTheCredentialWhenThePathSmugglesAScheme() throws Exception {
        String received = sendThroughProxy(request("x.evil.example://api.example.com/collect").withHeader("Host", "api.example.com"), InetSocketAddress.createUnresolved("api.example.com", 80));

        assertThat(received, startsWith("get http://api.example.com/x.evil.example://api.example.com/collect "));
        assertThat(received, not(containsString("x-credential")));
    }

    @Test
    public void shouldNotSendTheCredentialWhenThePathIsAlreadyAnAbsoluteUri() throws Exception {
        String received = sendThroughProxy(request("http://api.example.com/collect").withHeader("Host", "api.example.com"), InetSocketAddress.createUnresolved("api.example.com", 80));

        assertThat(received, startsWith("get http://api.example.com/http://api.example.com/collect "));
        assertThat(received, not(containsString("x-credential")));
    }

    @Test
    public void shouldNotSendTheCredentialWhenThePathIsEmptyAndTheQueryNamesAnotherAuthority() throws Exception {
        HttpRequest emptyPath = request("").withHeader("Host", "api.example.com")
            .withQueryStringParameters(new Parameters().withEntry("@evil.com", "").withRawParameterString("@evil.com"));

        String received = sendThroughProxy(emptyPath, InetSocketAddress.createUnresolved("api.example.com", 80));

        assertThat(received, startsWith("get http://api.example.com/?@evil.com "));
        assertThat(received, not(containsString("x-credential")));
    }

    @Test
    public void shouldNotSendTheRequestWhenTheHostHeaderIsNotStrictlyAHostAndPort() {
        assertRefusedBeforeReachingTheProxy(request("/latest/meta-data/").withHeader("Host", "api.example.com?.evil.example"));
    }

    @Test
    public void shouldNotSendTheRequestWhenItHasTwoHostHeaders() {
        assertRefusedBeforeReachingTheProxy(request("/collect").withHeader("Host", "api.example.com", "evil.example"));
    }

    private void assertRefusedBeforeReachingTheProxy(HttpRequest request) {
        NettyHttpClient client = new NettyHttpClient(configuration().forwardCredentialHeadersByHost("http://api.example.com:80=X-Credential:PATH"), new MockServerLogger(), group, List.of(proxyConfiguration(HTTP, "127.0.0.1:" + fakeProxy.getLocalPort())), true);

        ExecutionException refused = assertThrows(ExecutionException.class, () -> client.sendRequest(request, InetSocketAddress.createUnresolved("api.example.com", 80)).get(30, TimeUnit.SECONDS));

        assertThat(refused.getCause(), instanceOf(MalformedForwardTargetException.class));
        assertThat(proxyReceived.isDone(), is(false));
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int read;
        while (!head.toString().endsWith("\r\n\r\n") && (read = in.read()) != -1) {
            head.append((char) read);
        }
        return head.toString();
    }
}

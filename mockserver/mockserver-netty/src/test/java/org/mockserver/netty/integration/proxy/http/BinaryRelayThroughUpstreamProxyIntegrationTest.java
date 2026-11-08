package org.mockserver.netty.integration.proxy.http;

import io.netty.buffer.ByteBufUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.mock.Expectation;
import org.mockserver.netty.integration.proxy.direct.StartTlsUpstream;
import org.mockserver.socket.PortFactory;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.BinaryResponse.binaryResponse;
import static org.mockserver.netty.integration.proxy.direct.StartTlsUpstream.SSL_REQUEST;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A binary connection whose upstream connection goes through forwardSocksProxy, forwardHttpsProxy or forwardHttpProxy
 * keeps one upstream connection for its life, tunnelled through that proxy (SOCKS5, or {@code CONNECT} for either HTTP
 * proxy), so a session protocol can be proxied through it: in the clear, upgraded to TLS part way through, or TLS from
 * its first byte. A destination on noProxyHosts is relayed directly, and one forwardProxyBlockPrivateNetworks refuses
 * is not tunnelled either, nor is one that is not exactly one host, which each proxy could read as another. The upstream is {@link StartTlsUpstream}, which answers a PostgreSQL-like session on each
 * connection it accepts.
 */
public class BinaryRelayThroughUpstreamProxyIntegrationTest {

    private static final long TIMEOUT_SECONDS = 30;
    private static final byte[] STARTUP = {0, 0, 0, 23, 0, 3, 0, 0, 'u', 's', 'e', 'r', 0, 'p', 'o', 's', 't', 'g', 'r', 'e', 's', 0, 0};
    private static final byte[] AUTHENTICATION_OK_AND_READY = {'R', 0, 0, 0, 8, 0, 0, 0, 0, 'Z', 0, 0, 0, 5, 'I'};
    private static final byte[] QUERY = {'Q', 0, 0, 0, 13, 'S', 'E', 'L', 'E', 'C', 'T', ' ', '1', 0};
    private static final byte[] SYNC = {'S', 0, 0, 0, 4};
    private static final byte[] READY = {'Z', 0, 0, 0, 5, 'I'};

    private ClientAndServer mockServer;
    private final List<Socket> sockets = new ArrayList<>();

    @After
    public void closeSocketsAndStopMockServer() {
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing what a test may already have closed
            }
        }
        stopQuietly(mockServer);
    }

    private Socket connectThrough(Configuration configuration, StartTlsUpstream upstream) throws IOException {
        mockServer = startClientAndServer(configuration, "127.0.0.1", upstream.port(), PortFactory.findFreePort());
        Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort());
        sockets.add(socket);
        socket.setSoTimeout((int) (TIMEOUT_SECONDS * 1000));
        socket.setTcpNoDelay(true);
        return socket;
    }

    private SSLSocket startTls(Socket socket) throws Exception {
        SSLSocket tls = StartTlsUpstream.startTlsAsClient(socket, "127.0.0.1", "TLSv1.3", false);
        sockets.add(tls);
        return tls;
    }

    private static StartTlsUpstream session() throws IOException {
        return new StartTlsUpstream()
            .answering(STARTUP, AUTHENTICATION_OK_AND_READY)
            .answering(QUERY, READY)
            .answering(SYNC, READY);
    }

    private static void exchange(Socket socket, byte[] message, byte[] expectedReply) throws IOException {
        socket.getOutputStream().write(message);
        socket.getOutputStream().flush();
        assertThat("reply to " + ByteBufUtil.hexDump(message), ByteBufUtil.hexDump(socket.getInputStream().readNBytes(expectedReply.length)), is(ByteBufUtil.hexDump(expectedReply)));
    }

    private static void runSession(Socket socket) throws IOException {
        exchange(socket, STARTUP, AUTHENTICATION_OK_AND_READY);
        exchange(socket, QUERY, READY);
        exchange(socket, SYNC, READY);
    }

    private static List<String> sessionMessages(String how) {
        List<String> messages = new ArrayList<>();
        for (byte[] message : new byte[][]{STARTUP, QUERY, SYNC}) {
            messages.add(how + " " + ByteBufUtil.hexDump(message));
        }
        return messages;
    }

    private static void assertOneUpstreamConnectionCarried(StartTlsUpstream upstream, List<String> messages) throws InterruptedException {
        StartTlsUpstream.Connection connection = upstream.awaitConnection(1, TIMEOUT_SECONDS * 1000);
        assertThat("one upstream connection for the session", upstream.connections().size(), is(1));
        assertThat(connection.messages(), is(messages));
    }

    @Test
    public void shouldRelayASessionOnOneConnectionTunnelledThroughForwardSocksProxy() throws Exception {
        try (RecordingUpstreamProxy socksProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.SOCKS5, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session()) {
            Socket client = connectThrough(configuration().forwardSocksProxy(socksProxy.address()), upstream);

            runSession(client);

            assertOneUpstreamConnectionCarried(upstream, sessionMessages("clear"));
            assertThat("one tunnel, to the target", socksProxy.destinations(), contains("ipv4 127.0.0.1:" + upstream.port()));
        }
    }

    @Test
    public void shouldRelayASessionOnOneConnectionTunnelledThroughForwardHttpsProxy() throws Exception {
        try (RecordingUpstreamProxy connectProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.CONNECT, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session()) {
            Socket client = connectThrough(configuration().forwardHttpsProxy(connectProxy.address()), upstream);

            runSession(client);

            assertOneUpstreamConnectionCarried(upstream, sessionMessages("clear"));
            assertThat("one tunnel, to the target", connectProxy.destinations(), contains("127.0.0.1:" + upstream.port()));
        }
    }

    @Test
    public void shouldRelayASessionOnOneConnectionTunnelledWithConnectThroughForwardHttpProxy() throws Exception {
        try (RecordingUpstreamProxy connectProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.CONNECT, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session()) {
            Socket client = connectThrough(configuration().forwardHttpProxy(connectProxy.address()), upstream);

            runSession(client);

            assertOneUpstreamConnectionCarried(upstream, sessionMessages("clear"));
            assertThat("one tunnel, to the target", connectProxy.destinations(), contains("127.0.0.1:" + upstream.port()));
        }
    }

    @Test
    public void shouldOpenATunnelThroughForwardHttpProxyWithTlsForAClientThatStartsWithTls() throws Exception {
        try (RecordingUpstreamProxy connectProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.CONNECT, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session().startingWithTls()) {
            Socket client = connectThrough(configuration().forwardHttpProxy(connectProxy.address()), upstream);

            runSession(startTls(client));

            assertOneUpstreamConnectionCarried(upstream, sessionMessages("TLS"));
            assertThat("not around the proxy", connectProxy.destinations(), contains("127.0.0.1:" + upstream.port()));
        }
    }

    @Test
    public void shouldAnswerAMessageFromABinaryExpectationOnAConnectionTunnelledThroughForwardHttpProxy() throws Exception {
        byte[] canned = {'Z', 0, 0, 0, 5, 'T'};
        try (RecordingUpstreamProxy connectProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.CONNECT, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session()) {
            Socket client = connectThrough(configuration().forwardHttpProxy(connectProxy.address()), upstream);
            mockServer.upsert(new Expectation(binaryRequest(QUERY)).thenRespondWithBinary(binaryResponse(canned)));

            exchange(client, STARTUP, AUTHENTICATION_OK_AND_READY);
            exchange(client, QUERY, canned);
            exchange(client, SYNC, READY);

            List<String> forwarded = new ArrayList<>();
            forwarded.add("clear " + ByteBufUtil.hexDump(STARTUP));
            forwarded.add("clear " + ByteBufUtil.hexDump(SYNC));
            assertOneUpstreamConnectionCarried(upstream, forwarded);
            assertThat("one tunnel, to the target", connectProxy.destinations(), contains("127.0.0.1:" + upstream.port()));
        }
    }

    @Test
    public void shouldSendTheForwardProxyCredentialsWithTheConnectThroughForwardHttpProxy() throws Exception {
        try (RecordingUpstreamProxy connectProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.CONNECT, TIMEOUT_SECONDS).requiringCredentials("proxy-user", "proxy-password");
             StartTlsUpstream upstream = session()) {
            Socket client = connectThrough(configuration()
                .forwardHttpProxy(connectProxy.address())
                .forwardProxyAuthenticationUsername("proxy-user")
                .forwardProxyAuthenticationPassword("proxy-password"), upstream);

            runSession(client);

            assertOneUpstreamConnectionCarried(upstream, sessionMessages("clear"));
            assertThat(connectProxy.destinations(), contains("127.0.0.1:" + upstream.port()));
            assertThat(connectProxy.refused(), is(empty()));
        }
    }

    @Test
    public void shouldCloseTheClientAndSayWhyWhenForwardHttpProxyRefusesTheTunnel() throws Exception {
        try (RecordingUpstreamProxy connectProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.CONNECT, TIMEOUT_SECONDS).requiringCredentials("proxy-user", "proxy-password");
             StartTlsUpstream upstream = session()) {
            Socket client = connectThrough(configuration().logLevel("WARN").forwardHttpProxy(connectProxy.address()), upstream);

            client.getOutputStream().write(STARTUP);
            client.getOutputStream().flush();

            assertThat("the client is closed", readAfterClose(client), is("closed"));
            assertThat("the proxy refused the tunnel", connectProxy.refused(), contains("127.0.0.1:" + upstream.port()));
            assertThat("nothing reached the upstream", upstream.connections(), is(empty()));
            List<String> warnings = new ArrayList<>();
            for (String logged : mockServer.retrieveLogMessagesArray(null)) {
                if (logged.contains("refused to open a CONNECT tunnel")) {
                    warnings.add(logged);
                }
            }
            assertThat("said once", warnings, hasSize(1));
            assertThat(warnings.get(0), containsString("unable to connect to"));
            assertThat(warnings.get(0), containsString("set by forwardHttpProxy refused to open a CONNECT tunnel to 127.0.0.1:" + upstream.port() + " with status 407 Proxy Authentication Required"));
            assertThat(warnings.get(0), containsString("it asks for credentials: set forwardProxyAuthenticationUsername and forwardProxyAuthenticationPassword"));
        }
    }

    @Test
    public void shouldRelayADestinationOnNoProxyHostsDirectlyInsteadOfThroughForwardHttpProxy() throws Exception {
        try (RecordingUpstreamProxy connectProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.CONNECT, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session()) {
            Socket client = connectThrough(configuration().forwardHttpProxy(connectProxy.address()).noProxyHosts("127.0.0.1"), upstream);

            runSession(client);

            assertOneUpstreamConnectionCarried(upstream, sessionMessages("clear"));
            assertThat(connectProxy.destinations(), is(empty()));
        }
    }

    @Test
    public void shouldUpgradeATunnelledUpstreamConnectionToTlsWhenTheClientTurnsTlsOn() throws Exception {
        try (RecordingUpstreamProxy socksProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.SOCKS5, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session()) {
            Socket client = connectThrough(configuration().forwardSocksProxy(socksProxy.address()), upstream);

            exchange(client, SSL_REQUEST, new byte[]{'S'});
            runSession(startTls(client));

            List<String> messages = new ArrayList<>();
            messages.add("clear " + ByteBufUtil.hexDump(SSL_REQUEST));
            messages.addAll(sessionMessages("TLS"));
            assertOneUpstreamConnectionCarried(upstream, messages);
            assertThat("only the SSLRequest in the clear", ByteBufUtil.hexDump(upstream.connections().get(0).receivedInTheClear()), is(ByteBufUtil.hexDump(SSL_REQUEST)));
            assertThat(socksProxy.destinations(), contains("ipv4 127.0.0.1:" + upstream.port()));
        }
    }

    @Test
    public void shouldOpenATunnelledUpstreamConnectionWithTlsForAClientThatStartsWithTls() throws Exception {
        try (RecordingUpstreamProxy connectProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.CONNECT, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session().startingWithTls()) {
            Socket client = connectThrough(configuration().forwardHttpsProxy(connectProxy.address()), upstream);

            runSession(startTls(client));

            assertOneUpstreamConnectionCarried(upstream, sessionMessages("TLS"));
            assertThat("nothing in the clear past the tunnel", upstream.connections().get(0).receivedInTheClear().length, is(0));
            assertThat(connectProxy.destinations(), contains("127.0.0.1:" + upstream.port()));
        }
    }

    @Test
    public void shouldRelayADestinationOnNoProxyHostsDirectlyInsteadOfThroughForwardSocksProxy() throws Exception {
        try (RecordingUpstreamProxy socksProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.SOCKS5, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session()) {
            Socket client = connectThrough(configuration().forwardSocksProxy(socksProxy.address()).noProxyHosts("127.0.0.1"), upstream);

            runSession(client);

            assertOneUpstreamConnectionCarried(upstream, sessionMessages("clear"));
            assertThat(socksProxy.destinations(), is(empty()));
        }
    }

    @Test
    public void shouldRelayADestinationOnNoProxyHostsDirectlyInsteadOfThroughForwardHttpsProxy() throws Exception {
        try (RecordingUpstreamProxy connectProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.CONNECT, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session()) {
            Socket client = connectThrough(configuration().forwardHttpsProxy(connectProxy.address()).noProxyHosts("127.0.0.1"), upstream);

            runSession(client);

            assertOneUpstreamConnectionCarried(upstream, sessionMessages("clear"));
            assertThat(connectProxy.destinations(), is(empty()));
        }
    }

    @Test
    public void shouldNotTunnelToADestinationThatMayNotBeForwardedTo() throws Exception {
        try (RecordingUpstreamProxy socksProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.SOCKS5, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session()) {
            Socket client = connectThrough(configuration().forwardSocksProxy(socksProxy.address()).forwardProxyBlockPrivateNetworks(true), upstream);

            client.getOutputStream().write(STARTUP);
            client.getOutputStream().flush();

            assertThat("the client is closed", readAfterClose(client), is("closed"));
            assertThat("nothing asked of the proxy", socksProxy.destinations(), is(empty()));
            assertThat("nothing reached the upstream", upstream.connections(), is(empty()));
        }
    }

    private static Configuration throughEachProxy(int proxy, RecordingUpstreamProxy upstreamProxy) {
        return throughEachProxy(proxy, upstreamProxy, configuration().forwardProxyAllowedHosts("*.allowed.com"));
    }

    private static Configuration throughEachProxy(int proxy, RecordingUpstreamProxy upstreamProxy, Configuration configuration) {
        switch (proxy) {
            case 0:
                return configuration.forwardSocksProxy(upstreamProxy.address());
            case 1:
                return configuration.forwardHttpsProxy(upstreamProxy.address());
            default:
                return configuration.forwardHttpProxy(upstreamProxy.address());
        }
    }

    private static final String[] PROXY_SETTINGS = {"forwardSocksProxy", "forwardHttpsProxy", "forwardHttpProxy"};

    private Socket connectTo(Configuration configuration, String destinationHost, StartTlsUpstream upstream) throws IOException {
        mockServer = startClientAndServer(configuration, destinationHost, upstream.port(), PortFactory.findFreePort());
        Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort());
        sockets.add(socket);
        socket.setSoTimeout((int) (TIMEOUT_SECONDS * 1000));
        return socket;
    }

    @Test
    public void shouldNotTunnelAConnectionThroughAnyUpstreamProxyToADestinationThatIsNotOneHost() throws Exception {
        for (int proxy = 0; proxy < PROXY_SETTINGS.length; proxy++) {
            for (String host : new String[]{"user@secret.internal.example", "secret.internal.example/.allowed.com", "secret.internal.example?.allowed.com", "secret.internal.example:80/"}) {
                for (String allowedHosts : new String[]{"*.allowed.com", ""}) {
                    tryToTunnelTo(proxy, host, allowedHosts);
                }
            }
        }
    }

    private void tryToTunnelTo(int proxy, String host, String allowedHosts) throws Exception {
        String description = PROXY_SETTINGS[proxy] + " forwardProxyAllowedHosts=" + allowedHosts + " " + host;
        try (RecordingUpstreamProxy upstreamProxy = new RecordingUpstreamProxy(proxy == 0 ? RecordingUpstreamProxy.Protocol.SOCKS5 : RecordingUpstreamProxy.Protocol.CONNECT, TIMEOUT_SECONDS);
             StartTlsUpstream upstream = session()) {
            Socket client = connectTo(throughEachProxy(proxy, upstreamProxy, configuration().forwardProxyAllowedHosts(allowedHosts)), host, upstream);

            client.getOutputStream().write(STARTUP);
            client.getOutputStream().flush();

            assertThat(description + ": the client is closed", readAfterClose(client), is("closed"));
            assertThat(description + ": nothing asked of the proxy", upstreamProxy.destinations(), is(empty()));
            assertThat(description + ": nothing reached the upstream", upstream.connections(), is(empty()));
        } finally {
            stopQuietly(mockServer);
        }
    }

    @Test
    public void shouldTunnelAConnectionThroughEachUpstreamProxyToAWellFormedDestination() throws Exception {
        for (int proxy = 0; proxy < PROXY_SETTINGS.length; proxy++) {
            try (RecordingUpstreamProxy upstreamProxy = new RecordingUpstreamProxy(proxy == 0 ? RecordingUpstreamProxy.Protocol.SOCKS5 : RecordingUpstreamProxy.Protocol.CONNECT, TIMEOUT_SECONDS);
                 StartTlsUpstream upstream = session()) {
                runSession(connectTo(throughEachProxy(proxy, upstreamProxy), "db.allowed.com", upstream));

                assertOneUpstreamConnectionCarried(upstream, sessionMessages("clear"));
                assertThat(PROXY_SETTINGS[proxy], upstreamProxy.destinations(), contains((proxy == 0 ? "domain " : "") + "db.allowed.com:" + upstream.port()));
            } finally {
                stopQuietly(mockServer);
            }
        }
    }

    /** What a refused or closed connection gives a reader: nothing, an end of stream or an error. */
    private static String readAfterClose(Socket socket) {
        try {
            int read = socket.getInputStream().read();
            return read == -1 ? "closed" : "read " + read;
        } catch (IOException closed) {
            return "closed";
        }
    }
}

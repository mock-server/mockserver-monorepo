package org.mockserver.netty.integration.proxy.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Through forwardHttpProxy, with forwardProxyBlockPrivateNetworks on, a Host whose trailing characters or numeric form
 * would make the proxy read a different authority from the one MockServer checks is refused before anything reaches
 * the proxy, and a path cannot extend or replace the authority. The upstream proxy here records each request line and answers 200
 * without connecting anywhere; requests are written to MockServer as raw bytes so every Host and path reaches it as
 * given.
 */
public class ForwardHttpProxyRequestTargetIntegrationTest {

    private static final int SOCKET_TIMEOUT_MILLIS = 30_000;

    private RecordingHttpProxy upstreamProxy;
    private ClientAndServer mockServer;

    @Before
    public void startServers() throws IOException {
        upstreamProxy = new RecordingHttpProxy();
        mockServer = startClientAndServer(configuration()
            .logLevel("INFO")
            .forwardHttpProxy(upstreamProxy.address())
            .forwardProxyBlockPrivateNetworks(true));
    }

    @After
    public void stopServers() throws IOException {
        stopQuietly(mockServer);
        upstreamProxy.close();
    }

    @Test
    public void shouldRefuseAHostTheUpstreamProxyWouldReadAsAnotherDestination() throws IOException {
        String[][] refused = {
            {"GET /latest/meta-data/", "Host: 8.8.8.8:80@169.254.169.254"},
            {"GET /latest/meta-data/", "Host: 8.8.8.8@169.254.169.254"},
            {"GET /latest/meta-data/", "Host: 169.254.169.254?.8.8.8.8"},
            {"GET /latest/meta-data/", "Host: 8.8.8.8:80/@169.254.169.254"},
            {"GET /latest/meta-data/", "Host: 8.8.8.8\r\nHost: 169.254.169.254"},
            {"GET /latest/meta-data/", "Host: 169.254.169.254"},
            {"GET /latest/meta-data/", "Host: 0177.0.0.1"},
            {"GET /latest/meta-data/", "Host: 0251.0376.0251.0376"},
            {"GET /latest/meta-data/", "Host: 0x7f.1"},
            {"GET /latest/meta-data/", "Host: 2130706433"},
            {"GET /latest/meta-data/", "Host: 169.254.43518"}
        };
        for (String[] request : refused) {
            assertThat(request[1], statusLine(request[0], request[1]), is("HTTP/1.1 502 Bad Gateway"));
        }
        assertThat(upstreamProxy.requestLines(), is(empty()));
    }

    @Test
    public void shouldLogAHostThatIsNotOneHostAsRefusedRatherThanAsBlockedByPolicy() throws Exception {
        assertThat(statusLine("GET /x", "Host: 8.8.8.8:80@169.254.169.254"), is("HTTP/1.1 502 Bad Gateway"));

        String refusal = null;
        for (int attempt = 0; attempt < 50 && refusal == null; attempt++) {
            refusal = Arrays.stream(mockServer.retrieveLogMessagesArray(null))
                .filter(message -> message.contains("8.8.8.8:80@169.254.169.254") || message.contains("not exactly one host"))
                .filter(message -> message.contains("refused"))
                .findFirst().orElse(null);
            if (refusal == null) {
                Thread.sleep(100);
            }
        }
        assertThat(refusal, containsString("proxied request refused:"));
        assertThat(refusal, not(containsString("SSRF")));
        assertThat(upstreamProxy.requestLines(), is(empty()));
    }

    @Test
    public void shouldSendTheUpstreamProxyOnlyTheCheckedAuthorityWithAPathThatStartsWithASlash() throws IOException {
        assertThat(statusLine("GET /latest/meta-data/", "Host: 8.8.8.8"), is("HTTP/1.1 200 OK"));
        assertThat(statusLine("GET @169.254.169.254/latest/", "Host: 8.8.8.8"), is("HTTP/1.1 200 OK"));
        assertThat(statusLine("GET HTTP://169.254.169.254/latest/meta-data/", "Host: 8.8.8.8:80"), is("HTTP/1.1 200 OK"));
        assertThat(statusLine("GET /a%09b%00c%7F/", "Host: 8.8.8.8"), is("HTTP/1.1 200 OK"));

        assertThat(upstreamProxy.requestLines(), contains(
            "GET http://8.8.8.8/latest/meta-data/ HTTP/1.1",
            "GET http://8.8.8.8/@169.254.169.254/latest/ HTTP/1.1",
            "GET http://8.8.8.8:80/HTTP://169.254.169.254/latest/meta-data/ HTTP/1.1",
            "GET http://8.8.8.8/a%09b%00c%7F/ HTTP/1.1"
        ));
    }

    private String statusLine(String methodAndTarget, String hostHeaders) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), mockServer.getLocalPort())) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            OutputStream output = socket.getOutputStream();
            output.write((methodAndTarget + " HTTP/1.1\r\n" + hostHeaders + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1)).readLine();
        }
    }

    private static final class RecordingHttpProxy implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final List<String> requestLines = new CopyOnWriteArrayList<>();

        RecordingHttpProxy() throws IOException {
            serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::accept, ForwardHttpProxyRequestTargetIntegrationTest.class.getSimpleName() + "-upstreamProxy");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        InetSocketAddress address() {
            return new InetSocketAddress("127.0.0.1", serverSocket.getLocalPort());
        }

        List<String> requestLines() {
            return requestLines;
        }

        private void accept() {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    String requestLine = reader.readLine();
                    if (requestLine == null) {
                        continue;
                    }
                    requestLines.add(requestLine);
                    String header;
                    do {
                        header = reader.readLine();
                    } while (header != null && !header.isEmpty());
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\ncontent-length: 8\r\nconnection: close\r\n\r\nupstream".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (SocketException closed) {
                    return;
                } catch (IOException ignored) {
                    // one broken connection does not stop the proxy
                }
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}

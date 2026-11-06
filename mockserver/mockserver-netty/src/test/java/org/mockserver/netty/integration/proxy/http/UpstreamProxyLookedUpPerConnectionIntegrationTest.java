package org.mockserver.netty.integration.proxy.http;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * An upstream proxy set by name in forwardHttpProxy, forwardHttpsProxy or forwardSocksProxy is looked up for each
 * connection made to it, not once at startup: a proxy whose name did not resolve when MockServer started, as in a
 * Docker Compose file, is reached once it does, and one that moves to a new address is followed there. This holds for
 * HTTP forwards and for binary connections tunnelled through the proxy.
 * <p>
 * Runs in a fresh JVM that resolves names only from a hosts file this test rewrites, with the JVM's lookup cache off.
 */
public class UpstreamProxyLookedUpPerConnectionIntegrationTest {

    @ClassRule
    public static TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static String alternativeLoopback;
    private static String output;

    @BeforeClass
    public static void runProbe() throws Exception {
        alternativeLoopback = alternativeLoopback();
        File hostsFile = temporaryFolder.newFile("hosts");
        File securityProperties = temporaryFolder.newFile("java.security");
        File probeOutput = temporaryFolder.newFile("probe.log");
        Files.write(securityProperties.toPath(), "networkaddress.cache.ttl=0\nnetworkaddress.cache.negative.ttl=0\n".getBytes(StandardCharsets.ISO_8859_1));
        ProcessBuilder processBuilder = new ProcessBuilder(Arrays.asList(
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
            "-Djdk.net.hosts.file=" + hostsFile.getAbsolutePath(),
            "-Djava.security.properties=" + securityProperties.getAbsolutePath(),
            "-Dmockserver.logLevel=WARN",
            "-cp", System.getProperty("java.class.path"),
            Probe.class.getName(),
            hostsFile.getAbsolutePath(),
            alternativeLoopback != null ? alternativeLoopback : ""
        )).redirectErrorStream(true).redirectOutput(probeOutput);
        processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
        Process process = processBuilder.start();
        boolean exited = process.waitFor(180, TimeUnit.SECONDS);
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        output = new String(Files.readAllBytes(probeOutput.toPath()), StandardCharsets.UTF_8);
        assertThat(output, exited, is(true));
    }

    @Test
    public void shouldReachProxyWhoseNameResolvesOnlyAfterStartup() {
        assertThat(output, output, containsString("HTTP absent at startup: status=200 body=" + RecordingUpstreamProxy.HTTP_PROXY_BODY + " first=1"));
        assertThat(output, output, containsString("CONNECT absent at startup: status=200 body=destination first=1"));
        assertThat(output, output, containsString("SOCKS5 absent at startup: status=200 body=destination first=1"));
    }

    @Test
    public void shouldFollowProxyToItsNewAddress() {
        Assume.assumeTrue("needs a second loopback address, 127.0.0.2 or ::1", alternativeLoopback != null);
        assertThat(output, output, containsString("HTTP before move: status=200 body=" + RecordingUpstreamProxy.HTTP_PROXY_BODY + " first=1 second=0"));
        assertThat(output, output, containsString("HTTP after move: status=200 body=" + RecordingUpstreamProxy.HTTP_PROXY_BODY + " first=1 second=1"));
        assertThat(output, output, containsString("CONNECT before move: status=200 body=destination first=1 second=0"));
        assertThat(output, output, containsString("CONNECT after move: status=200 body=destination first=1 second=1"));
        assertThat(output, output, containsString("SOCKS5 before move: status=200 body=destination first=1 second=0"));
        assertThat(output, output, containsString("SOCKS5 after move: status=200 body=destination first=1 second=1"));
    }

    @Test
    public void shouldTunnelBinaryConnectionThroughProxyWhoseNameResolvesOnlyAfterStartup() {
        assertThat(output, output, containsString("CONNECT binary absent at startup: echoed=true first=1"));
        assertThat(output, output, containsString("SOCKS5 binary absent at startup: echoed=true first=1"));
    }

    @Test
    public void shouldFollowBinaryConnectionsProxyToItsNewAddress() {
        Assume.assumeTrue("needs a second loopback address, 127.0.0.2 or ::1", alternativeLoopback != null);
        assertThat(output, output, containsString("CONNECT binary before move: echoed=true first=1 second=0"));
        assertThat(output, output, containsString("CONNECT binary after move: echoed=true first=1 second=1"));
        assertThat(output, output, containsString("SOCKS5 binary before move: echoed=true first=1 second=0"));
        assertThat(output, output, containsString("SOCKS5 binary after move: echoed=true first=1 second=1"));
    }

    // 127.0.0.2 is loopback on Linux without configuration; macOS has only 127.0.0.1, but has ::1
    private static String alternativeLoopback() {
        for (String candidate : new String[]{"127.0.0.2", "::1"}) {
            try (ServerSocket probe = new ServerSocket()) {
                probe.bind(new InetSocketAddress(InetAddress.getByName(candidate), 0));
                return candidate;
            } catch (IOException notAvailable) {
                // try the next
            }
        }
        return null;
    }

    /**
     * For each kind of upstream proxy, starts MockServer with the proxy set by a name, sends an HTTP request, and, for
     * the tunnelling proxies, a binary connection's bytes, through it, and prints what came back and which proxy, on
     * which address, carried it.
     */
    public static class Probe {

        private static final long TIMEOUT_SECONDS = 30;
        // a PostgreSQL startup message: not HTTP, so relayed as binary
        private static final byte[] BINARY = {0, 0, 0, 23, 0, 3, 0, 0, 'u', 's', 'e', 'r', 0, 'p', 'o', 's', 't', 'g', 'r', 'e', 's', 0, 0};

        private interface Route {
            ClientAndServer start();

            String send() throws Exception;
        }

        public static void main(String[] arguments) throws Exception {
            Path hostsFile = Paths.get(arguments[0]);
            String alternative = arguments[1].isEmpty() ? null : arguments[1];
            writeHosts(hostsFile, null, null);
            EventLoopGroup clientEventLoopGroup = new NioEventLoopGroup(2);
            ClientAndServer destination = startClientAndServer();
            try (ServerSocket echo = echoServer()) {
                destination.when(request().withPath("/target")).respond(response().withBody("destination"));
                for (RecordingUpstreamProxy.Protocol protocol : RecordingUpstreamProxy.Protocol.values()) {
                    AtomicReference<ClientAndServer> started = new AtomicReference<>();
                    Route forward = new Route() {
                        @Override
                        public ClientAndServer start() {
                            return startClientAndServer();
                        }

                        @Override
                        public String send() {
                            return sendRequest(clientEventLoopGroup, started.get(), protocol, destination);
                        }
                    };
                    runScenarios(hostsFile, alternative, protocol, protocol.name(), forward, started);
                    if (protocol != RecordingUpstreamProxy.Protocol.HTTP) {
                        Route binary = new Route() {
                            @Override
                        public ClientAndServer start() {
                                return new ClientAndServer("127.0.0.1", echo.getLocalPort());
                            }

                            @Override
                            public String send() throws IOException {
                                return sendBinary(started.get());
                            }
                        };
                        runScenarios(hostsFile, alternative, protocol, protocol.name() + " binary", binary, started);
                    }
                }
            } finally {
                destination.stop();
                clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS);
                System.exit(0);
            }
        }

        private static void runScenarios(Path hostsFile, String alternative, RecordingUpstreamProxy.Protocol protocol, String label, Route route, AtomicReference<ClientAndServer> started) {
            String proxyName = label.toLowerCase().replace(' ', '-') + "-proxy.test";
            try (RecordingUpstreamProxy first = new RecordingUpstreamProxy(protocol, TIMEOUT_SECONDS)) {
                started.set(startWithProxy(protocol, proxyName, first.address().getPort(), route));
                try {
                    writeHosts(hostsFile, proxyName, "127.0.0.1");
                    System.out.println(label + " absent at startup: " + attempt(route) + " first=" + first.destinations().size());
                } finally {
                    started.get().stop();
                }
            } catch (Throwable failed) {
                System.out.println(label + " absent at startup failed: " + failed);
            }
            if (alternative == null) {
                return;
            }
            proxyName = "moving-" + proxyName;
            try (RecordingUpstreamProxy first = new RecordingUpstreamProxy(protocol, TIMEOUT_SECONDS);
                 RecordingUpstreamProxy second = new RecordingUpstreamProxy(protocol, TIMEOUT_SECONDS, new InetSocketAddress(InetAddress.getByName(alternative), first.address().getPort()))) {
                writeHosts(hostsFile, proxyName, "127.0.0.1");
                started.set(startWithProxy(protocol, proxyName, first.address().getPort(), route));
                try {
                    System.out.println(label + " before move: " + attempt(route) + " first=" + first.destinations().size() + " second=" + second.destinations().size());
                    writeHosts(hostsFile, proxyName, alternative);
                    System.out.println(label + " after move: " + attempt(route) + " first=" + first.destinations().size() + " second=" + second.destinations().size());
                } finally {
                    started.get().stop();
                }
            } catch (Throwable failed) {
                System.out.println(label + " move failed: " + failed);
            }
        }

        private static String attempt(Route route) {
            try {
                return route.send();
            } catch (Exception failed) {
                return "error=" + failed;
            }
        }

        // set as a property, as an operator sets it, which MockServer reads when it starts
        private static ClientAndServer startWithProxy(RecordingUpstreamProxy.Protocol protocol, String proxyName, int proxyPort, Route route) {
            Consumer<String> property = protocol == RecordingUpstreamProxy.Protocol.HTTP ? ConfigurationProperties::forwardHttpProxy
                : protocol == RecordingUpstreamProxy.Protocol.CONNECT ? ConfigurationProperties::forwardHttpsProxy
                : ConfigurationProperties::forwardSocksProxy;
            property.accept(proxyName + ":" + proxyPort);
            try {
                return route.start();
            } finally {
                property.accept("");
            }
        }

        private static String sendRequest(EventLoopGroup clientEventLoopGroup, ClientAndServer mockServer, RecordingUpstreamProxy.Protocol protocol, ClientAndServer destination) {
            try {
                HttpResponse response = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
                    .sendRequest(
                        request().withPath("/target").withSecure(protocol == RecordingUpstreamProxy.Protocol.CONNECT).withHeader(HOST.toString(), "localhost:" + destination.getLocalPort()),
                        new InetSocketAddress("127.0.0.1", mockServer.getLocalPort())
                    )
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return "status=" + response.getStatusCode() + " body=" + response.getBodyAsString();
            } catch (Exception failed) {
                return "error=" + failed;
            }
        }

        private static String sendBinary(ClientAndServer mockServer) throws IOException {
            try (Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort())) {
                socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                socket.getOutputStream().write(BINARY);
                socket.getOutputStream().flush();
                return "echoed=" + Arrays.equals(socket.getInputStream().readNBytes(BINARY.length), BINARY);
            }
        }

        private static ServerSocket echoServer() throws IOException {
            ServerSocket echo = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            RecordingUpstreamProxy.daemon(() -> {
                while (!echo.isClosed()) {
                    Socket connection = echo.accept();
                    RecordingUpstreamProxy.daemon(() -> RecordingUpstreamProxy.copy(connection, connection));
                }
            });
            return echo;
        }

        private static void writeHosts(Path hostsFile, String name, String address) throws IOException {
            String hosts = "127.0.0.1 localhost\n" + (name != null ? address + " " + name + "\n" : "");
            Files.write(hostsFile, hosts.getBytes(StandardCharsets.ISO_8859_1));
        }
    }
}

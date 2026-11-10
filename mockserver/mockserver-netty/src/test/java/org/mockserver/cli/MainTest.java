package org.mockserver.cli;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.authentication.ControlPlaneMutualTlsConfigurationException;
import org.mockserver.client.MockServerClient;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.dns.DnsStartupException;
import org.mockserver.netty.http3.Http3Server;
import org.mockserver.netty.http3.Http3StartupException;
import org.mockserver.netty.integration.proxy.http.HttpProxyChainedIntegrationTest;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.PortFactory;
import org.mockserver.socket.tls.DataPlaneMutualTlsConfigurationException;
import org.mockserver.socket.tls.DataPlaneMutualTlsTrust;
import org.mockserver.socket.tls.PEMToFile;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * @author jamesdbloom
 */
public class MainTest {

    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void startEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(MainTest.class.getSimpleName() + "-eventLoop"));
        // These tests invoke Main.main(...) in-process, including failure paths (invalid port/host/log-level,
        // no port). Disable the JVM-terminating exit so a non-zero command does not kill the test JVM.
        Main.exitOnNonZeroCode = false;
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        Main.usageShown = false;
        Main.exitOnNonZeroCode = true;
    }

    @After
    public void clearUsageShown() {
        Main.usageShown = false;
    }

    /**
     * Start MockServer in-process via {@link Main#main} on an OS-assigned ephemeral port — the given
     * arguments MUST request port 0 — and return the actual bound port read back from
     * {@link Main#getLastStartedPorts()}. Binding port 0 and reading the real port back removes the
     * find-a-free-port-then-bind TOCTOU race entirely: {@code PortFactory.findFreePort()} binds and
     * closes a socket on the same ephemeral pool every other {@code bind(0)} uses, so another bind in
     * this shared test JVM (surefire reuses one fork) can steal the port before the server binds it.
     * Binding port 0 chooses no port ahead of the bind, so nothing can race it. If the server fails
     * to start, {@code getLastStartedPorts()} stays {@code null} and this fails loudly.
     */
    private static int startServerOnEphemeralPort(String... mainArgs) {
        Main.lastStartedPorts = null;
        Main.main(mainArgs);
        List<Integer> ports = Main.getLastStartedPorts();
        assertThat("MockServer failed to bind an ephemeral port (Main.getLastStartedPorts())",
            ports != null && !ports.isEmpty(), is(true));
        return ports.get(0);
    }

    /**
     * Start MockServer in-process on a SPECIFIC requested port, retrying on a bind conflict with a
     * fresh {@link PortFactory#findFreePort()} port. Used only where a concrete, known port carries
     * meaning (here, to prove a CLI port overrides a system-property port), so port 0 cannot be used.
     * The CLI swallows a failed bind — {@link Main.RunCommand#run()} logs it and shows usage without
     * rethrowing — so the only visible signal is {@link Main#getLastStartedPorts()} staying
     * {@code null}; on that signal we stop anything half-started and retry with a new port (max 3),
     * failing loudly if every attempt fails so a genuine (non-bind) defect is never masked.
     *
     * @param argsForPort builds the {@code Main.main} arguments for a chosen port
     * @return the requested port that was successfully bound
     */
    private static int startServerOnRequestedPortWithRetry(IntFunction<String[]> argsForPort) {
        int port = -1;
        for (int attempt = 1; attempt <= 3; attempt++) {
            port = PortFactory.findFreePort();
            Main.lastStartedPorts = null;
            Main.main(argsForPort.apply(port));
            List<Integer> ports = Main.getLastStartedPorts();
            if (ports != null && !ports.isEmpty()) {
                return port;
            }
            // The CLI swallowed a (probable) bind conflict; stop any half-started server and retry.
            stopQuietly(new MockServerClient("127.0.0.1", port));
        }
        throw new AssertionError("MockServer failed to start on a requested port after 3 attempts (last port " + port + ")");
    }

    @Test
    public void shouldStartMockServer() {
        // given
        MockServerClient mockServerClient = null;
        Level originalLogLevel = ConfigurationProperties.logLevel();

        try {
            // when
            final int freePort = startServerOnEphemeralPort(
                "-serverPort", "0",
                "-logLevel", "DEBUG"
            );
            mockServerClient = new MockServerClient("127.0.0.1", freePort);

            // then
            assertThat("mockServerClient.hasStarted",  mockServerClient.hasStarted(), is(true));
            assertThat("ConfigurationProperties.logLevel", ConfigurationProperties.logLevel().toString(), is("DEBUG"));
        } finally {
            ConfigurationProperties.logLevel(originalLogLevel.toString());
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStopTheServerWhenTheJvmShutsDownWhenRecordOpenStreamsOnStopIsOn() throws IOException {
        try (PersistenceProperties ignored = new PersistenceProperties()) {
            ConfigurationProperties.recordOpenStreamsOnStop(true);
            shouldStopTheServerWhenItsShutdownHookRuns();
        }
    }

    @Test
    public void shouldRemoveTheShutdownHookOnceTheServerStopsAnotherWay() throws InterruptedException {
        boolean original = ConfigurationProperties.recordOpenStreamsOnStop();
        Main.lastShutdownHook = null;
        Thread hook = null;
        try {
            ConfigurationProperties.recordOpenStreamsOnStop(true);
            int port = startServerOnEphemeralPort("-serverPort", "0");
            hook = Main.lastShutdownHook;
            assertThat("a shutdown hook was made", hook != null, is(true));

            // when
            stopQuietly(new MockServerClient("127.0.0.1", port));

            // then - removing it again fails once the server's stop has removed it
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean stillRegistered = true;
            while (stillRegistered && System.nanoTime() < deadline) {
                stillRegistered = isRegistered(hook);
                if (stillRegistered) {
                    MILLISECONDS.sleep(20);
                }
            }
            assertThat("the stopped server's shutdown hook is removed", stillRegistered, is(false));
        } finally {
            ConfigurationProperties.recordOpenStreamsOnStop(original);
            Main.lastShutdownHook = null;
            if (hook != null && isRegistered(hook)) {
                Runtime.getRuntime().removeShutdownHook(hook);
                hook.run();
            }
        }
    }

    /**
     * Whether {@code hook} is registered, found by registering it again, which fails only when it already is.
     */
    private static boolean isRegistered(Thread hook) {
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalArgumentException alreadyRegistered) {
            return true;
        }
        Runtime.getRuntime().removeShutdownHook(hook);
        return false;
    }

    @Test
    public void shouldNotAddAShutdownHookWhenNothingIsPersisted() throws IOException {
        MockServerClient mockServerClient = null;
        try (PersistenceProperties ignored = new PersistenceProperties()) {
            mockServerClient = new MockServerClient("127.0.0.1", startServerOnEphemeralPort("-serverPort", "0"));

            assertThat(Main.lastShutdownHook, is(nullValue()));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStopTheServerWhenTheJvmShutsDownWhenPersistExpectationsIsOn() throws IOException {
        try (PersistenceProperties ignored = new PersistenceProperties()) {
            ConfigurationProperties.persistExpectations(true);
            shouldStopTheServerWhenItsShutdownHookRuns();
        }
    }

    @Test
    public void shouldStopTheServerWhenTheJvmShutsDownWhenPersistRecordedExpectationsIsOn() throws IOException {
        try (PersistenceProperties ignored = new PersistenceProperties()) {
            ConfigurationProperties.persistRecordedExpectations(true);
            shouldStopTheServerWhenItsShutdownHookRuns();
        }
    }

    @Test
    public void shouldStopTheServerWhenTheJvmShutsDownWhenPersistRecordedRequestsToDiskIsOn() throws IOException {
        try (PersistenceProperties ignored = new PersistenceProperties()) {
            ConfigurationProperties.persistRecordedRequestsToDisk(true);
            shouldStopTheServerWhenItsShutdownHookRuns();
        }
    }

    private void shouldStopTheServerWhenItsShutdownHookRuns() {
        Main.lastShutdownHook = null;
        Thread hook = null;
        boolean hookRan = false;
        try {
            int port = startServerOnEphemeralPort("-serverPort", "0");
            hook = Main.lastShutdownHook;
            assertThat("a shutdown hook was made", hook != null, is(true));
            assertThat("the shutdown hook is registered with the JVM", Runtime.getRuntime().removeShutdownHook(hook), is(true));

            // when - as on SIGINT or SIGTERM
            hook.run();
            hookRan = true;

            // then
            assertThat("the server has stopped", new MockServerClient("127.0.0.1", port).hasStopped(), is(true));

            // and running it again, as a second signal would, returns at once
            long secondRunStart = System.nanoTime();
            hook.run();
            assertThat("a second run returns at once", System.nanoTime() - secondRunStart < TimeUnit.SECONDS.toNanos(5), is(true));
        } finally {
            Main.lastShutdownHook = null;
            if (hook != null && !hookRan) {
                Runtime.getRuntime().removeShutdownHook(hook);
                hook.run();
            }
        }
    }

    /**
     * Turns every property {@code Configuration.persistsOnStop()} reads off, points each persisted file at a temporary
     * file, and restores them all on close.
     */
    private static class PersistenceProperties implements AutoCloseable {
        private final boolean persistExpectations = ConfigurationProperties.persistExpectations();
        private final boolean persistRecordedExpectations = ConfigurationProperties.persistRecordedExpectations();
        private final boolean persistRecordedRequestsToDisk = ConfigurationProperties.persistRecordedRequestsToDisk();
        private final boolean recordOpenStreamsOnStop = ConfigurationProperties.recordOpenStreamsOnStop();
        private final String persistedExpectationsPath = ConfigurationProperties.persistedExpectationsPath();
        private final String persistedRecordedExpectationsPath = ConfigurationProperties.persistedRecordedExpectationsPath();
        private final String persistedRecordedRequestsPath = ConfigurationProperties.persistedRecordedRequestsPath();
        private final java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("mainShutdownHook");

        PersistenceProperties() throws IOException {
            ConfigurationProperties.persistExpectations(false);
            ConfigurationProperties.persistRecordedExpectations(false);
            ConfigurationProperties.persistRecordedRequestsToDisk(false);
            ConfigurationProperties.recordOpenStreamsOnStop(false);
            ConfigurationProperties.persistedExpectationsPath(directory.resolve("persistedExpectations.json").toString());
            ConfigurationProperties.persistedRecordedExpectationsPath(directory.resolve("persistedRecordedExpectations.json").toString());
            ConfigurationProperties.persistedRecordedRequestsPath(directory.resolve("recordedRequests.ndjson").toString());
        }

        @Override
        public void close() {
            ConfigurationProperties.persistExpectations(persistExpectations);
            ConfigurationProperties.persistRecordedExpectations(persistRecordedExpectations);
            ConfigurationProperties.persistRecordedRequestsToDisk(persistRecordedRequestsToDisk);
            ConfigurationProperties.recordOpenStreamsOnStop(recordOpenStreamsOnStop);
            ConfigurationProperties.persistedExpectationsPath(persistedExpectationsPath);
            ConfigurationProperties.persistedRecordedExpectationsPath(persistedRecordedExpectationsPath);
            ConfigurationProperties.persistedRecordedRequestsPath(persistedRecordedRequestsPath);
        }
    }

    @Test
    public void shouldStartMockServerWithRemotePortAndHost() {
        // given
        MockServerClient mockServerClient = null;
        EchoServer echoServer = new EchoServer(false);
        try {
            echoServer.withNextResponse(response("port_forwarded_response"));

            // when
            final int freePort = startServerOnEphemeralPort(
                "-serverPort", "0",
                "-proxyRemotePort", String.valueOf(echoServer.getPort()),
                "-proxyRemoteHost", "127.0.0.1"
            );
            mockServerClient = new MockServerClient("127.0.0.1", freePort);
            final HttpResponse response = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
                .sendRequest(
                    request()
                        .withHeader(HOST.toString(), "127.0.0.1:" + freePort),
                    10,
                    TimeUnit.SECONDS
                );

            // then
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("response.getBodyAsString", response.getBodyAsString(), is("port_forwarded_response"));
        } finally {
            stopQuietly(mockServerClient);
            stopQuietly(echoServer);
        }
    }

    @Test
    public void shouldStartMockServerWithRemotePort() {
        // given
        MockServerClient mockServerClient = null;
        EchoServer echoServer = new EchoServer(false);
        try {
            echoServer.withNextResponse(response("port_forwarded_response"));

            // when
            final int freePort = startServerOnEphemeralPort(
                "-serverPort", "0",
                "-proxyRemotePort", String.valueOf(echoServer.getPort())
            );
            mockServerClient = new MockServerClient("127.0.0.1", freePort);
            final HttpResponse response = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
                .sendRequest(
                    request()
                        .withHeader(HOST.toString(), "127.0.0.1:" + freePort),
                    10,
                    TimeUnit.SECONDS
                );

            // then
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("response.getBodyAsString", response.getBodyAsString(), is("port_forwarded_response"));
        } finally {
            stopQuietly(mockServerClient);
            stopQuietly(echoServer);
        }
    }

    @Test
    public void shouldStartMockServerFromSystemProperty() {
        MockServerClient mockServerClient = null;

        try {
            System.setProperty("mockserver.serverPort", "0");

            final int freePort = startServerOnEphemeralPort();
            mockServerClient = new MockServerClient("127.0.0.1", freePort);

            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
        } finally {
            System.clearProperty("mockserver.serverPort");
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldPreferCliArgOverSystemProperty() {
        final int sysPropPort = PortFactory.findFreePort();
        MockServerClient mockServerClient = null;

        try {
            System.setProperty("mockserver.serverPort", String.valueOf(sysPropPort));

            // This test needs a concrete CLI port (distinct from the system-property port) to prove
            // the CLI arg wins, so it cannot use port 0. Bind it resiliently instead of racily.
            final int cliPort = startServerOnRequestedPortWithRetry(port -> new String[]{"-serverPort", String.valueOf(port)});
            mockServerClient = new MockServerClient("127.0.0.1", cliPort);

            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));

            MockServerClient wrongPortClient = new MockServerClient("127.0.0.1", sysPropPort);
            boolean sysPropPortRunning;
            try {
                sysPropPortRunning = wrongPortClient.hasStarted();
            } catch (Exception e) {
                sysPropPortRunning = false;
            }
            assertTrue("server should NOT be running on system property port", !sysPropPortRunning);
        } finally {
            System.clearProperty("mockserver.serverPort");
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStartMockServerFromPropertiesFile() {
        MockServerClient mockServerClient = null;

        try {
            ConfigurationProperties.PROPERTIES.setProperty("mockserver.serverPort", "0");

            final int freePort = startServerOnEphemeralPort();
            mockServerClient = new MockServerClient("127.0.0.1", freePort);

            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
        } finally {
            ConfigurationProperties.PROPERTIES.remove("mockserver.serverPort");
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldPrintOutUsageForInvalidServerPort() throws UnsupportedEncodingException {
        // given
        PrintStream originalPrintStream = Main.systemOut;
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(byteArrayOutputStream, true, StandardCharsets.UTF_8.name());

            // when
            Main.main("-serverPort", "A");

            // then
            String actual = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
            String expected = NEW_LINE +
                "   =====================================================================================================" + NEW_LINE +
                "   serverPort value \"A\" is invalid, please specify a comma separated list of ports i.e. \"1080,1081,1082\"" + NEW_LINE +
                "   =====================================================================================================" + NEW_LINE +
                NEW_LINE +
                Main.USAGE;
            assertThat(actual, is(expected));
        } finally {
            Main.systemOut = originalPrintStream;
        }
    }

    @Test
    public void shouldPrintOutUsageForInvalidRemotePort() throws UnsupportedEncodingException {
        // given
        final int freePort = PortFactory.findFreePort();
        PrintStream originalPrintStream = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            // when
            Main.main("-serverPort", String.valueOf(freePort), "-proxyRemotePort", "A");

            // then
            String actual = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            String expected = NEW_LINE +
                "   =======================================================================" + NEW_LINE +
                "   proxyRemotePort value \"A\" is invalid, please specify a port i.e. \"1080\"" + NEW_LINE +
                "   =======================================================================" + NEW_LINE +
                NEW_LINE +
                Main.USAGE;
            assertThat(actual, is(expected));
        } finally {
            Main.systemOut = originalPrintStream;
        }
    }

    @Test
    public void shouldPrintOutUsageForInvalidRemoteHost() throws UnsupportedEncodingException {
        // given
        final int freePort = PortFactory.findFreePort();
        PrintStream originalPrintStream = Main.systemOut;
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(byteArrayOutputStream, true, StandardCharsets.UTF_8.name());

            // when
            Main.main("-serverPort", String.valueOf(freePort), "-proxyRemoteHost", "%^&*(");

            // then
            String actual = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
            String expected = NEW_LINE +
                "   ====================================================================================================" + NEW_LINE +
                "   proxyRemoteHost value \"%^&*(\" is invalid, please specify a host name i.e. \"localhost\" or \"127.0.0.1\"" + NEW_LINE +
                "   ====================================================================================================" + NEW_LINE +
                NEW_LINE +
                Main.USAGE;
            assertThat(actual, is(expected));
        } finally {
            Main.systemOut = originalPrintStream;
        }
    }

    @Test
    public void shouldPrintOutUsageForInvalidLogLevel() throws UnsupportedEncodingException {
        // given
        final int freePort = PortFactory.findFreePort();
        PrintStream originalPrintStream = Main.systemOut;
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(byteArrayOutputStream, true, StandardCharsets.UTF_8.name());

            // when
            Main.main("-serverPort", String.valueOf(freePort), "-logLevel", "FOO");

            // then
            String actual = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
            String expected = NEW_LINE +
                "   ====================================================================================================================================================================================================" + NEW_LINE +
                "   logLevel value \"FOO\" is invalid, please specify one of SL4J levels: \"TRACE\", \"DEBUG\", \"INFO\", \"WARN\", \"ERROR\", \"OFF\" or the Java Logger levels: \"FINEST\", \"FINE\", \"INFO\", \"WARNING\", \"SEVERE\", \"OFF\"" + NEW_LINE +
                "   ====================================================================================================================================================================================================" + NEW_LINE +
                NEW_LINE +
                Main.USAGE;
            assertThat(actual, is(expected));
        } finally {
            Main.systemOut = originalPrintStream;
        }
    }

    /**
     * A server that FAILS TO START (here, a failed port bind) must make the CLI report a non-zero exit
     * code so a shell/CI caller can detect the failure. Before the fix, {@link Main.RunCommand#run()}
     * caught the bind exception, logged it, showed usage and returned normally — so picocli returned 0
     * and the process exited 0 despite the server never coming up. We hold a port with a plain
     * {@link ServerSocket} (no TOCTOU window — the port is genuinely occupied) and then ask the CLI to
     * bind that same port; the bind fails deep in {@code MockServer.createServerBootstrap} and the exit
     * code must be 1.
     */
    @Test
    public void shouldExitNonZeroWhenServerFailsToStart() throws IOException {
        // given — hold a port so the CLI's bind attempt is guaranteed to fail
        Main.lastStartedPorts = null;
        try (ServerSocket occupied = new ServerSocket(0)) {
            int takenPort = occupied.getLocalPort();

            // when — ask the CLI to start MockServer on the already-bound port
            Main.main("-serverPort", String.valueOf(takenPort));

            // then — the failed bind surfaces as a non-zero exit code, and nothing reports as started
            assertThat("Main.lastExitCode after a failed start", Main.lastExitCode, is(1));
            assertThat("Main.getLastStartedPorts() after a failed start", Main.getLastStartedPorts(), is(nullValue()));
        }
    }

    /**
     * A validation / usage error (here, a non-numeric server port) is NOT a startup failure and must
     * keep its documented exit code of 0 — the fix for a failed START must not change the exit code of
     * the usage-error path (the {@code catch (IllegalArgumentException)} branch of
     * {@link Main.RunCommand#run()}).
     */
    @Test
    public void shouldExitZeroForInvalidServerPortUsageError() {
        // when — an invalid (non-numeric) port is a usage error, handled and printed without a startup attempt
        Main.main("-serverPort", "A");

        // then — usage errors are exit code 0 (unchanged documented behaviour)
        assertThat("Main.lastExitCode after a usage/validation error", Main.lastExitCode, is(0));
    }

    @Test
    public void shouldExitNonZeroWhenTheHttp3PortCannotBeBound() throws IOException {
        Assume.assumeTrue("native QUIC not available on this platform", Http3Server.isQuicAvailable());
        Main.lastStartedPorts = null;
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("0.0.0.0", 0));
            ConfigurationProperties.http3Port(((InetSocketAddress) otherApplication.getLocalAddress()).getPort());

            Main.main("-serverPort", "0");

            assertThat("Main.lastExitCode when HTTP/3 could not start", Main.lastExitCode, is(1));
            assertThat("nothing reports as started", Main.getLastStartedPorts(), is(nullValue()));
        } finally {
            ConfigurationProperties.http3Port(0);
        }
    }

    // its cause is an IllegalArgumentException, which the run command takes for a usage error and exits 0
    @Test
    public void shouldExitNonZeroWhenTheHttp3PortIsNotAPort() {
        Assume.assumeTrue("native QUIC not available on this platform", Http3Server.isQuicAvailable());
        Main.lastStartedPorts = null;
        try {
            ConfigurationProperties.http3Port(70000);

            Main.main("-serverPort", "0");

            assertThat("Main.lastExitCode when HTTP/3 could not start", Main.lastExitCode, is(1));
            assertThat("nothing reports as started", Main.getLastStartedPorts(), is(nullValue()));
        } finally {
            ConfigurationProperties.http3Port(0);
        }
    }

    @Test
    public void shouldPrintTheMessageAloneWhenTheHttp3PortCannotBeBound() {
        Http3StartupException portHeld = new Http3StartupException(8443, new BindException("Address already in use"));

        String printed = printedToSystemErr(() -> Main.logStartupFailure(new RuntimeException("wrapped by the caller", portHeld), true));

        assertThat(printed, containsString("HTTP/3 is enabled (http3Port=8443) but UDP port 8443 could not be bound, so MockServer cannot start:"
            + " free the port if another application holds it, choose a different http3Port, or remove http3Port to run without HTTP/3 (underlying error: BindException: Address already in use)"));
        assertThat("the fix must not be buried in a stack trace", printed, not(containsString("\tat ")));
    }

    @Test
    public void shouldPrintTheMessageAloneWhenControlPlaneMutualTlsIsRefused() {
        String printed = printedToSystemErr(() -> Main.logStartupFailure(new RuntimeException("wrapped by the caller", new ControlPlaneMutualTlsConfigurationException()), true));

        assertThat(printed, containsString("controlPlaneTLSMutualAuthenticationRequired is enabled without controlPlaneTLSMutualAuthenticationCAChain"));
        assertThat("the fix must not be buried in a stack trace", printed, not(containsString("\tat ")));
    }

    @Test
    public void shouldPrintTheMessageAloneWhenDataPlaneMutualTlsIsRefused() {
        DataPlaneMutualTlsConfigurationException refused = assertThrows(DataPlaneMutualTlsConfigurationException.class,
            () -> DataPlaneMutualTlsTrust.clientCertificateTrust("", PEMToFile.x509FromPEMFile(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE), false, null));

        String printed = printedToSystemErr(() -> Main.logStartupFailure(new RuntimeException("wrapped by the caller", refused), true));

        assertThat(printed, containsString("tlsMutualAuthenticationRequired is enabled while MockServer's CA certificate is the default CA bundled with MockServer"));
        assertThat("the fix must not be buried in a stack trace", printed, not(containsString("\tat ")));
    }

    @Test
    public void shouldPrintTheStackTraceOfAnyOtherHttp3StartFailure() {
        Http3StartupException unexplained = new Http3StartupException(8443, new IllegalStateException("could not build the TLS context"));

        String printed = printedToSystemErr(() -> Main.logStartupFailure(unexplained, true));

        assertThat(printed, containsString("exception while starting: HTTP/3 is enabled (http3Port=8443) but its server could not start on UDP port 8443, so MockServer cannot start:"
            + " fix the underlying error or remove http3Port to run without HTTP/3 (underlying error: IllegalStateException: could not build the TLS context)"));
        assertThat(printed, containsString("\tat "));
    }

    @Test
    public void shouldExitNonZeroWhenTheDnsPortCannotBeBound() throws IOException {
        Main.lastStartedPorts = null;
        try (DatagramChannel otherApplication = DatagramChannel.open(StandardProtocolFamily.INET)) {
            otherApplication.bind(new InetSocketAddress("127.0.0.1", 0));
            ConfigurationProperties.dnsEnabled(true);
            ConfigurationProperties.dnsPort(((InetSocketAddress) otherApplication.getLocalAddress()).getPort());

            Main.main("-serverPort", "0");

            assertThat("Main.lastExitCode when DNS could not start", Main.lastExitCode, is(1));
            assertThat("nothing reports as started", Main.getLastStartedPorts(), is(nullValue()));
        } finally {
            dnsOffAndStopAnythingStarted();
        }
    }

    // its cause is an IllegalArgumentException, which the run command takes for a usage error and exits 0
    @Test
    public void shouldExitNonZeroWhenTheDnsPortIsNotAPort() {
        Main.lastStartedPorts = null;
        try {
            ConfigurationProperties.dnsEnabled(true);
            ConfigurationProperties.dnsPort(70000);

            Main.main("-serverPort", "0");

            assertThat("Main.lastExitCode when DNS could not start", Main.lastExitCode, is(1));
            assertThat("nothing reports as started", Main.getLastStartedPorts(), is(nullValue()));
        } finally {
            dnsOffAndStopAnythingStarted();
        }
    }

    private static void dnsOffAndStopAnythingStarted() {
        ConfigurationProperties.dnsEnabled(false);
        ConfigurationProperties.dnsPort(0);
        List<Integer> started = Main.getLastStartedPorts();
        if (started != null && !started.isEmpty()) {
            stopQuietly(new MockServerClient("127.0.0.1", started.get(0)));
        }
    }

    @Test
    public void shouldPrintTheMessageAloneWhenTheDnsPortCannotBeBound() {
        DnsStartupException portHeld = DnsStartupException.portCouldNotBeOpenedOrBound(5353, new BindException("Address already in use"));

        String printed = printedToSystemErr(() -> Main.logStartupFailure(new RuntimeException("wrapped by the caller", portHeld), true));

        assertThat(printed, containsString("DNS mocking is enabled (dnsEnabled=true, dnsPort=5353) but UDP port 5353 could not be opened or bound, so MockServer cannot start:"
            + " free the port if another application holds it, choose a different dnsPort (0 picks a free port, and a port below 1024 can need extra privileges),"
            + " or set dnsEnabled=false to run without DNS mocking (underlying error: BindException: Address already in use)"));
        assertThat("the fix must not be buried in a stack trace", printed, not(containsString("\tat ")));
    }

    @Test
    public void shouldPrintTheStackTraceOfAnyOtherDnsStartFailure() {
        DnsStartupException unexplained = DnsStartupException.serverCouldNotStart(5353, new IllegalStateException("the event loop is shut down"));

        String printed = printedToSystemErr(() -> Main.logStartupFailure(unexplained, true));

        assertThat(printed, containsString("exception while starting: DNS mocking is enabled (dnsEnabled=true, dnsPort=5353) but its server could not start on UDP port 5353, so MockServer cannot start:"
            + " fix the underlying error or set dnsEnabled=false to run without DNS mocking (underlying error: IllegalStateException: the event loop is shut down)"));
        assertThat(printed, containsString("\tat "));
    }

    private static String printedToSystemErr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

}

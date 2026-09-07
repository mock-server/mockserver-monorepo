package org.mockserver.cli;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.PortFactory;
import org.mockserver.test.Retries;
import org.mockserver.version.Version;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Tests for the picocli-based CLI — subcommands, new flags, preprocessing, help, and version.
 */
public class MainCliTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void startEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(MainCliTest.class.getSimpleName() + "-eventLoop"));
        // These tests invoke Main.main(...) in-process, including failure paths (unknown flag, no port,
        // failed import). Disable the JVM-terminating exit so a non-zero command does not kill the test JVM.
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

    // ---- Preprocessing (bare → run) ----

    @Test
    public void shouldPrependRunWhenNoSubcommand() {
        String[] result = Main.preprocessArguments("-p", "1080");
        assertThat(result[0], is("run"));
        assertThat(result[1], is("-p"));
        assertThat(result[2], is("1080"));
        assertThat(result.length, is(3));
    }

    @Test
    public void shouldNotPrependRunWhenRunSubcommandPresent() {
        String[] result = Main.preprocessArguments("run", "-p", "1080");
        assertThat(result[0], is("run"));
        assertThat(result.length, is(3));
    }

    @Test
    public void shouldNotPrependRunWhenProxySubcommandPresent() {
        String[] result = Main.preprocessArguments("proxy", "--to", "localhost:8080");
        assertThat(result[0], is("proxy"));
        assertThat(result.length, is(3));
    }

    @Test
    public void shouldNotPrependRunWhenVersionSubcommandPresent() {
        String[] result = Main.preprocessArguments("version");
        assertThat(result[0], is("version"));
        assertThat(result.length, is(1));
    }

    @Test
    public void shouldNotPrependRunWhenUiSubcommandPresent() {
        String[] result = Main.preprocessArguments("ui", "-p", "1080");
        assertThat(result[0], is("ui"));
        assertThat(result.length, is(3));
    }

    @Test
    public void shouldNormalizeLegacySingleDashHelpToTopLevel() {
        // "-help" should behave like "--help" (top-level overview), not cluster into "run -h"
        String[] result = Main.preprocessArguments("-help");
        assertThat(result[0], is("--help"));
        assertThat(result.length, is(1));
    }

    @Test
    public void shouldNormalizeLegacySingleDashVersionToTopLevel() {
        String[] result = Main.preprocessArguments("-version");
        assertThat(result[0], is("--version"));
        assertThat(result.length, is(1));
    }

    @Test
    public void shouldNotPrependRunWhenOpenApiSubcommandPresent() {
        String[] result = Main.preprocessArguments("openapi", "./petstore.yaml", "-p", "1080");
        assertThat(result[0], is("openapi"));
        assertThat(result.length, is(4));
    }

    @Test
    public void shouldPrependRunForEmptyArgs() {
        String[] result = Main.preprocessArguments();
        assertThat(result[0], is("run"));
        assertThat(result.length, is(1));
    }

    @Test
    public void shouldPrependRunForLegacyFlags() {
        String[] result = Main.preprocessArguments("-serverPort", "1080", "-proxyRemotePort", "80");
        assertThat(result[0], is("run"));
        assertThat(result[1], is("-serverPort"));
        assertThat(result.length, is(5));
    }

    // ---- New --port flag via "run" subcommand ----

    @Test
    public void shouldStartWithNewPortFlag() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("run", "-p", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStartWithLongPortFlag() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("run", "--port", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStartWithBarePortFlagNoSubcommand() {
        // "mockserver -p 1080" should work (bare → run)
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("-p", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStartWithNewLogLevelFlag() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        Level originalLogLevel = ConfigurationProperties.logLevel();

        try {
            Main.main("run", "-p", String.valueOf(freePort), "-l", "WARN");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("ConfigurationProperties.logLevel", ConfigurationProperties.logLevel().toString(), is("WARN"));
        } finally {
            ConfigurationProperties.logLevel(originalLogLevel.toString());
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldPreserveExplicitProxySetupLoggingOptOutOnStandaloneLaunch() {
        // The standalone launcher auto-enables proxySetupLogging only when the user has NOT configured
        // it in any source. An explicit value here (set via the setter, which is what
        // proxySetupLoggingConfigured() detects — the same guard branch that also honours a
        // mockserver.properties opt-out) must be preserved, not clobbered to true.
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        boolean originalProxySetupLogging = ConfigurationProperties.proxySetupLogging();

        try {
            ConfigurationProperties.proxySetupLogging(false); // explicit user opt-out
            Main.main("run", "-p", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("explicit proxySetupLogging=false must be preserved by the standalone launcher",
                ConfigurationProperties.proxySetupLogging(), is(false));
        } finally {
            ConfigurationProperties.proxySetupLogging(originalProxySetupLogging);
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStartWithLongLogLevelFlag() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        Level originalLogLevel = ConfigurationProperties.logLevel();

        try {
            Main.main("run", "--port", String.valueOf(freePort), "--log-level", "DEBUG");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("ConfigurationProperties.logLevel", ConfigurationProperties.logLevel().toString(), is("DEBUG"));
        } finally {
            ConfigurationProperties.logLevel(originalLogLevel.toString());
            stopQuietly(mockServerClient);
        }
    }

    // ---- Version subcommand ----

    @Test
    public void shouldPrintVersion() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("version");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            String expectedVersion = "MockServer " + Version.getVersion();
            assertThat("version subcommand output should match the --version output",
                output.trim(), is(expectedVersion));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    // ---- Help output ----

    @Test
    public void shouldPrintHelpForRunSubcommand() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("run", "--help");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat(output, containsString("--port"));
            assertThat(output, containsString("--proxy-to"));
            assertThat(output, containsString("--openapi"));
            assertThat(output, containsString("--init"));
            assertThat(output, containsString("--persist"));
            assertThat(output, containsString("--log-level"));
            assertThat(output, containsString("--dev"));
            // Legacy flags should be hidden
            assertThat(output, not(containsString("-serverPort")));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldPrintTopLevelHelp() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("--help");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat(output, containsString("run"));
            assertThat(output, containsString("proxy"));
            assertThat(output, containsString("openapi"));
            assertThat(output, containsString("version"));
            assertThat(output, containsString("mock, proxy & record"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    // ---- --print-config (effective configuration diagnostic) ----

    @Test
    public void shouldPrintEffectiveConfigWithSourceAndRedactSecrets() throws Exception {
        PrintStream originalOut = Main.systemOut;
        String previousNonSensitive = System.getProperty("mockserver.maxExpectations");
        String previousSensitive = System.getProperty("mockserver.llmApiKey");
        // effectiveConfiguration is cache-first; a prior test may have cached maxExpectations, which
        // would otherwise win over the system property set below. Clear the cache entries so the
        // system-property tier is authoritative, and restore them afterwards.
        String previousCachedNonSensitive = getPropertyCacheEntry("mockserver.maxExpectations");
        String previousCachedSensitive = getPropertyCacheEntry("mockserver.llmApiKey");
        try {
            clearPropertyCacheEntry("mockserver.maxExpectations");
            clearPropertyCacheEntry("mockserver.llmApiKey");
            System.setProperty("mockserver.maxExpectations", "4242");
            System.setProperty("mockserver.llmApiKey", "super-secret-cli-value");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            // --print-config must print the effective config and exit WITHOUT starting a server,
            // so no port is needed and no MockServer is left running.
            Main.main("--print-config");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("non-sensitive key shows value and system-property source",
                output, containsString("mockserver.maxExpectations = 4242   [system-property]"));
            assertThat("sensitive key is redacted with its source still reported",
                output, containsString("mockserver.llmApiKey = ***REDACTED***   [system-property]"));
            assertThat("secret value must never be printed",
                output, not(containsString("super-secret-cli-value")));
            assertThat("a never-set key reports the built-in default",
                output, containsString("[default]"));
        } finally {
            Main.systemOut = originalOut;
            restorePropertyCacheEntry("mockserver.maxExpectations", previousCachedNonSensitive);
            restorePropertyCacheEntry("mockserver.llmApiKey", previousCachedSensitive);
            if (previousNonSensitive != null) {
                System.setProperty("mockserver.maxExpectations", previousNonSensitive);
            } else {
                System.clearProperty("mockserver.maxExpectations");
            }
            if (previousSensitive != null) {
                System.setProperty("mockserver.llmApiKey", previousSensitive);
            } else {
                System.clearProperty("mockserver.llmApiKey");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> propertyCache() throws Exception {
        java.lang.reflect.Field cacheField = ConfigurationProperties.class.getDeclaredField("propertyCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        return cache instanceof java.util.Map ? (java.util.Map<String, String>) cache : null;
    }

    private static String getPropertyCacheEntry(String key) throws Exception {
        java.util.Map<String, String> cache = propertyCache();
        return cache != null ? cache.get(key) : null;
    }

    private static void clearPropertyCacheEntry(String key) throws Exception {
        java.util.Map<String, String> cache = propertyCache();
        if (cache != null) {
            cache.remove(key);
        }
    }

    private static void restorePropertyCacheEntry(String key, String previousValue) throws Exception {
        java.util.Map<String, String> cache = propertyCache();
        if (cache != null) {
            if (previousValue != null) {
                cache.put(key, previousValue);
            } else {
                cache.remove(key);
            }
        }
    }

    // ---- OpenAPI --init and --persist wire into ConfigurationProperties ----

    @Test
    public void shouldSetOpenApiPathViaRunSubcommand() throws IOException {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        String originalOpenApiPath = ConfigurationProperties.initializationOpenAPIPath();
        File tempSpec = tempFolder.newFile("test-spec.yaml");

        try {
            Main.main("run", "-p", String.valueOf(freePort), "--openapi", tempSpec.getAbsolutePath());
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat(ConfigurationProperties.initializationOpenAPIPath(), is(tempSpec.getAbsolutePath()));
        } finally {
            ConfigurationProperties.initializationOpenAPIPath(originalOpenApiPath != null ? originalOpenApiPath : "");
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldSetInitPathViaRunSubcommand() throws IOException {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        String originalInitPath = ConfigurationProperties.initializationJsonPath();
        File tempInit = tempFolder.newFile("test-init.json");

        try {
            Main.main("run", "-p", String.valueOf(freePort), "--init", tempInit.getAbsolutePath());
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat(ConfigurationProperties.initializationJsonPath(), is(tempInit.getAbsolutePath()));
        } finally {
            ConfigurationProperties.initializationJsonPath(originalInitPath != null ? originalInitPath : "");
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldSetPersistPathViaRunSubcommand() throws IOException {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        boolean originalPersist = ConfigurationProperties.persistExpectations();
        String originalPersistPath = ConfigurationProperties.persistedExpectationsPath();
        File tempPersist = tempFolder.newFile("test-persist.json");

        try {
            Main.main("run", "-p", String.valueOf(freePort), "--persist", tempPersist.getAbsolutePath());
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat(ConfigurationProperties.persistExpectations(), is(true));
            assertThat(ConfigurationProperties.persistedExpectationsPath(), is(tempPersist.getAbsolutePath()));
        } finally {
            ConfigurationProperties.persistExpectations(originalPersist);
            ConfigurationProperties.persistedExpectationsPath(originalPersistPath != null ? originalPersistPath : "");
            stopQuietly(mockServerClient);
        }
    }

    // ---- OpenAPI subcommand ----

    @Test
    public void shouldSetOpenApiPathViaOpenApiSubcommand() throws IOException {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        String originalOpenApiPath = ConfigurationProperties.initializationOpenAPIPath();
        File tempSpec = tempFolder.newFile("test-spec-sub.yaml");

        try {
            Main.main("openapi", tempSpec.getAbsolutePath(), "-p", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat(ConfigurationProperties.initializationOpenAPIPath(), is(tempSpec.getAbsolutePath()));
        } finally {
            ConfigurationProperties.initializationOpenAPIPath(originalOpenApiPath != null ? originalOpenApiPath : "");
            stopQuietly(mockServerClient);
        }
    }

    // ---- Legacy backward compat via picocli ----

    @Test
    public void shouldStartWithLegacyServerPortViaNewParser() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("-serverPort", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldStartWithLegacyFlagsAndLogLevel() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        Level originalLogLevel = ConfigurationProperties.logLevel();

        try {
            Main.main("-serverPort", String.valueOf(freePort), "-logLevel", "DEBUG");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("ConfigurationProperties.logLevel", ConfigurationProperties.logLevel().toString(), is("DEBUG"));
        } finally {
            ConfigurationProperties.logLevel(originalLogLevel.toString());
            stopQuietly(mockServerClient);
        }
    }

    // ---- Proxy-to scheme inference (fix 1) ----

    @Test
    public void shouldProxyToHttpsUrlInferringPort443ViaRunSubcommand() {
        final int freePort = PortFactory.findFreePort();
        EchoServer echoServer = new EchoServer(false);
        echoServer.withNextResponse(response("proxied_via_https_scheme"));
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            // Use the echo server port with an explicit port in the URL to verify proxy mode works,
            // but first verify parseProxyTarget resolves https:// → 443
            String[] parsed = Main.parseProxyTarget("https://api.example.com");
            assertThat("host from https URL", parsed[0], is("api.example.com"));
            assertThat("port inferred from https scheme", parsed[1], is("443"));

            // Now test that the actual proxy mode works end-to-end with a real echo server
            Main.main("run", "-p", String.valueOf(freePort),
                "--proxy-to", "http://127.0.0.1:" + echoServer.getPort());

            HttpResponse httpResponse = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
                .sendRequest(
                    request().withHeader(HOST.toString(), "127.0.0.1:" + freePort),
                    10, TimeUnit.SECONDS
                );
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("response.getBodyAsString", httpResponse.getBodyAsString(), is("proxied_via_https_scheme"));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldProxyToHttpsUrlViaProxySubcommand() {
        final int freePort = PortFactory.findFreePort();
        EchoServer echoServer = new EchoServer(false);
        echoServer.withNextResponse(response("proxied_via_proxy_cmd"));
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("proxy", "--to", "http://127.0.0.1:" + echoServer.getPort(),
                "-p", String.valueOf(freePort));

            HttpResponse httpResponse = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
                .sendRequest(
                    request().withHeader(HOST.toString(), "127.0.0.1:" + freePort),
                    10, TimeUnit.SECONDS
                );
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("response.getBodyAsString", httpResponse.getBodyAsString(), is("proxied_via_proxy_cmd"));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldParseProxyTargetWithHttpScheme() {
        String[] parsed = Main.parseProxyTarget("http://api.example.com");
        assertThat("host", parsed[0], is("api.example.com"));
        assertThat("port", parsed[1], is("80"));
    }

    @Test
    public void shouldParseProxyTargetWithHttpsSchemeAndPath() {
        String[] parsed = Main.parseProxyTarget("https://api.example.com/v2/path");
        assertThat("host", parsed[0], is("api.example.com"));
        assertThat("port", parsed[1], is("443"));
    }

    @Test
    public void shouldParseProxyTargetWithExplicitPortOverridingScheme() {
        String[] parsed = Main.parseProxyTarget("https://api.example.com:8443");
        assertThat("host", parsed[0], is("api.example.com"));
        assertThat("port", parsed[1], is("8443"));
    }

    @Test
    public void shouldParseProxyTargetHostColonPort() {
        String[] parsed = Main.parseProxyTarget("myhost:9090");
        assertThat("host", parsed[0], is("myhost"));
        assertThat("port", parsed[1], is("9090"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectProxyTargetWithNoPortAndNoScheme() {
        PrintStream originalOut = Main.systemOut;
        try {
            // Suppress the validation error box from polluting test output
            Main.systemOut = new PrintStream(new ByteArrayOutputStream(), true);
            Main.parseProxyTarget("api.example.com");
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldRejectProxyTargetWithNoPortProducesCleanError() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            try {
                Main.parseProxyTarget("api.example.com");
            } catch (IllegalArgumentException e) {
                // expected
            }

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat(output, containsString("proxy target"));
            assertThat(output, containsString("has no port"));
            assertThat(output, containsString("--proxy-to api.example.com:8080"));
            assertThat(output, containsString("https://api.example.com"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldParseProxyTargetWithIPv6Brackets() {
        String[] parsed = Main.parseProxyTarget("[::1]:8080");
        assertThat("host", parsed[0], is("::1"));
        assertThat("port", parsed[1], is("8080"));
    }

    @Test
    public void shouldParseProxyTargetWithHttpsAndIPv6() {
        String[] parsed = Main.parseProxyTarget("https://[::1]");
        assertThat("host", parsed[0], is("::1"));
        assertThat("port", parsed[1], is("443"));
    }

    // ---- Help subcommand (fix 2) ----

    @Test
    public void shouldPrintHelpViaHelpSubcommand() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("help");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat(output, containsString("run"));
            assertThat(output, containsString("proxy"));
            assertThat(output, containsString("openapi"));
            assertThat(output, containsString("version"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldPrintHelpForSubcommandViaHelpSubcommand() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("help", "proxy");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat(output, containsString("--to"));
            assertThat(output, containsString("--port"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    // ---- Picocli-era error paths show concise picocli usage, not legacy USAGE blob ----

    @Test
    public void shouldShowPicocliUsageForProxyToWithNoPort() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        PrintStream originalErr = Main.systemErr;
        try {
            ByteArrayOutputStream outBaos = new ByteArrayOutputStream();
            ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(outBaos, true, StandardCharsets.UTF_8.name());
            Main.systemErr = new PrintStream(errBaos, true, StandardCharsets.UTF_8.name());

            Main.main("run", "--proxy-to", "api.example.com");

            String stdoutOutput = new String(outBaos.toByteArray(), StandardCharsets.UTF_8);
            // Should contain the actionable "has no port" error in the bordered box
            assertThat("should contain the 'has no port' validation message",
                stdoutOutput, containsString("has no port"));
            // Should contain picocli's concise usage (the run subcommand usage)
            assertThat("should contain picocli --port option in concise usage",
                stdoutOutput, containsString("--port"));
            assertThat("should contain picocli --proxy-to option in concise usage",
                stdoutOutput, containsString("--proxy-to"));
            // Should NOT contain the legacy USAGE blob
            assertThat("should not contain legacy 'java -jar <path to' blob",
                stdoutOutput, not(containsString("java -jar <path to")));
            assertThat("should not contain legacy '-serverPort <port>' line",
                stdoutOutput, not(containsString("-serverPort <port>")));
        } finally {
            Main.systemOut = originalOut;
            Main.systemErr = originalErr;
        }
    }

    @Test
    public void shouldShowPicocliUsageForUnknownFlag() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        PrintStream originalErr = Main.systemErr;
        try {
            ByteArrayOutputStream outBaos = new ByteArrayOutputStream();
            ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(outBaos, true, StandardCharsets.UTF_8.name());
            Main.systemErr = new PrintStream(errBaos, true, StandardCharsets.UTF_8.name());

            Main.main("run", "--nonexistent-flag", "value");

            String stdoutOutput = new String(outBaos.toByteArray(), StandardCharsets.UTF_8);
            String stderrOutput = new String(errBaos.toByteArray(), StandardCharsets.UTF_8);
            String combinedOutput = stderrOutput + stdoutOutput;

            // Should contain the picocli error about the unknown option
            assertThat("should mention unknown option in error",
                stderrOutput, containsString("Unknown option"));
            // Should contain picocli's concise usage for the run subcommand
            assertThat("should contain picocli --port option in concise usage",
                stdoutOutput, containsString("--port"));
            assertThat("should contain picocli --proxy-to option in concise usage",
                stdoutOutput, containsString("--proxy-to"));
            // Should NOT contain the legacy USAGE blob
            assertThat("should not contain legacy 'java -jar <path to' blob",
                combinedOutput, not(containsString("java -jar <path to")));
            assertThat("should not contain legacy '-serverPort <port>' line",
                combinedOutput, not(containsString("-serverPort <port>")));
        } finally {
            Main.systemOut = originalOut;
            Main.systemErr = originalErr;
        }
    }

    // ---- Validation proxy flags (--validate-openapi / --validate-enforce) ----

    @Test
    public void shouldSetValidateOpenApiViaRunSubcommand() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        String originalSpec = ConfigurationProperties.validateProxyOpenAPISpec();

        try {
            Main.main("run", "-p", String.valueOf(freePort),
                "--validate-openapi", "https://petstore.swagger.io/v2/swagger.json");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("validateProxyOpenAPISpec should be set",
                ConfigurationProperties.validateProxyOpenAPISpec(),
                is("https://petstore.swagger.io/v2/swagger.json"));
        } finally {
            ConfigurationProperties.validateProxyOpenAPISpec(originalSpec != null ? originalSpec : "");
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldSetValidateEnforceViaRunSubcommand() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        boolean originalEnforce = ConfigurationProperties.validateProxyEnforce();

        try {
            Main.main("run", "-p", String.valueOf(freePort), "--validate-enforce");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("validateProxyEnforce should be true",
                ConfigurationProperties.validateProxyEnforce(), is(true));
        } finally {
            ConfigurationProperties.validateProxyEnforce(originalEnforce);
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldSetBothValidateFlagsViaRunSubcommand() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        String originalSpec = ConfigurationProperties.validateProxyOpenAPISpec();
        boolean originalEnforce = ConfigurationProperties.validateProxyEnforce();

        try {
            Main.main("run", "-p", String.valueOf(freePort),
                "--validate-openapi", "./petstore.yaml",
                "--validate-enforce");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("validateProxyOpenAPISpec should be set",
                ConfigurationProperties.validateProxyOpenAPISpec(), is("./petstore.yaml"));
            assertThat("validateProxyEnforce should be true",
                ConfigurationProperties.validateProxyEnforce(), is(true));
        } finally {
            ConfigurationProperties.validateProxyOpenAPISpec(originalSpec != null ? originalSpec : "");
            ConfigurationProperties.validateProxyEnforce(originalEnforce);
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldSetValidateFlagsViaProxySubcommand() {
        final int freePort = PortFactory.findFreePort();
        EchoServer echoServer = new EchoServer(false);
        echoServer.withNextResponse(response("proxied_validate"));
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        String originalSpec = ConfigurationProperties.validateProxyOpenAPISpec();
        boolean originalEnforce = ConfigurationProperties.validateProxyEnforce();

        try {
            Main.main("proxy", "--to", "http://127.0.0.1:" + echoServer.getPort(),
                "-p", String.valueOf(freePort),
                "--validate-openapi", "https://petstore.swagger.io/v2/swagger.json",
                "--validate-enforce");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("validateProxyOpenAPISpec should be set via proxy subcommand",
                ConfigurationProperties.validateProxyOpenAPISpec(),
                is("https://petstore.swagger.io/v2/swagger.json"));
            assertThat("validateProxyEnforce should be true via proxy subcommand",
                ConfigurationProperties.validateProxyEnforce(), is(true));
        } finally {
            ConfigurationProperties.validateProxyOpenAPISpec(originalSpec != null ? originalSpec : "");
            ConfigurationProperties.validateProxyEnforce(originalEnforce);
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldNotSetValidateDefaultsWithoutFlags() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        // Reset to known defaults before the test
        ConfigurationProperties.validateProxyOpenAPISpec("");
        ConfigurationProperties.validateProxyEnforce(false);

        try {
            Main.main("run", "-p", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("validateProxyOpenAPISpec should be empty by default",
                ConfigurationProperties.validateProxyOpenAPISpec(), is(""));
            assertThat("validateProxyEnforce should be false by default",
                ConfigurationProperties.validateProxyEnforce(), is(false));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldListValidateFlagsInRunHelp() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("run", "--help");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("run help should list --validate-openapi",
                output, containsString("--validate-openapi"));
            assertThat("run help should list --validate-enforce",
                output, containsString("--validate-enforce"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldListValidateFlagsInProxyHelp() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("help", "proxy");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("proxy help should list --validate-openapi",
                output, containsString("--validate-openapi"));
            assertThat("proxy help should list --validate-enforce",
                output, containsString("--validate-enforce"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    // ---- Dev mode (--dev) ----

    @Test
    public void shouldApplyDevModeDefaults() throws Exception {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            // Clear any explicitly-set maxLogEntries/maxExpectations (cache + system property) that a prior or
            // parallel test may have leaked into the shared static ConfigurationProperties state. Dev mode only
            // applies its defaults to properties the user has NOT explicitly set, so a leaked value would make
            // the assertions below non-deterministic.
            java.lang.reflect.Field cacheField = ConfigurationProperties.class.getDeclaredField("propertyCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> cache = (java.util.Map<String, String>) cacheField.get(null);
            cache.remove("mockserver.maxLogEntries");
            System.clearProperty("mockserver.maxLogEntries");
            cache.remove("mockserver.maxExpectations");
            System.clearProperty("mockserver.maxExpectations");

            Main.main("run", "-p", String.valueOf(freePort), "--dev");

            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("devMode should be enabled", ConfigurationProperties.devMode(), is(true));
            assertThat("maxLogEntries should be dev default (1000)",
                ConfigurationProperties.maxLogEntries(), is(1000));
            assertThat("maxExpectations should be dev default (1000)",
                ConfigurationProperties.maxExpectations(), is(1000));
        } finally {
            // Restore production defaults
            ConfigurationProperties.devMode(false);
            // Force maxLogEntries and maxExpectations back to a heap-based value
            // by setting them to a known large value (the static cache persists across tests)
            int heapBasedMaxLogEntries = Math.min((int) (ConfigurationProperties.heapAvailableInKB() / 8), 100000);
            int heapBasedMaxExpectations = Math.min((int) (ConfigurationProperties.heapAvailableInKB() / 10), 15000);
            ConfigurationProperties.maxLogEntries(heapBasedMaxLogEntries);
            ConfigurationProperties.maxExpectations(heapBasedMaxExpectations);
            stopQuietly(mockServerClient);
        }
    }

    // ---- No-port path shows a clean CLI usage error (not the legacy blob or empty config dump) ----

    @Test
    public void shouldShowCliUsageWhenNoPortSpecified() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        PrintStream originalErr = Main.systemErr;
        String originalSysProp = System.getProperty("mockserver.serverPort");
        Object originalProp = ConfigurationProperties.PROPERTIES.get("mockserver.serverPort");
        try {
            // Ensure no port is resolvable from any source so we hit the "no port" branch
            System.clearProperty("mockserver.serverPort");
            ConfigurationProperties.PROPERTIES.remove("mockserver.serverPort");

            ByteArrayOutputStream outBaos = new ByteArrayOutputStream();
            ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(outBaos, true, StandardCharsets.UTF_8.name());
            Main.systemErr = new PrintStream(errBaos, true, StandardCharsets.UTF_8.name());

            Main.main(); // no arguments → run with no resolvable port

            String out = new String(outBaos.toByteArray(), StandardCharsets.UTF_8);
            String err = new String(errBaos.toByteArray(), StandardCharsets.UTF_8);
            // Clean picocli usage for the run command
            assertThat("should show picocli --port option", out, containsString("--port"));
            // Actionable, concise error
            assertThat("should give a 'no port specified' error", err, containsString("no port specified"));
            // Not the legacy multi-line USAGE blob
            assertThat("should not print the legacy '-proxyRemotePort <port>' blob line",
                (out + err), not(containsString("-proxyRemotePort <port>")));
        } finally {
            Main.systemOut = originalOut;
            Main.systemErr = originalErr;
            if (originalSysProp != null) {
                System.setProperty("mockserver.serverPort", originalSysProp);
            }
            if (originalProp != null) {
                ConfigurationProperties.PROPERTIES.put("mockserver.serverPort", originalProp);
            }
        }
    }

    @Test
    public void shouldUseLauncherNameInNoPortError() throws UnsupportedEncodingException {
        PrintStream originalErr = Main.systemErr;
        PrintStream originalOut = Main.systemOut;
        String originalSysProp = System.getProperty("mockserver.serverPort");
        Object originalProp = ConfigurationProperties.PROPERTIES.get("mockserver.serverPort");
        try {
            System.clearProperty("mockserver.serverPort");
            ConfigurationProperties.PROPERTIES.remove("mockserver.serverPort");
            System.setProperty("mockserver.launcherName", "mockserver");

            ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name());
            Main.systemErr = new PrintStream(errBaos, true, StandardCharsets.UTF_8.name());

            Main.main();

            String err = new String(errBaos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("error should use the launcher command name, not java -jar",
                err, containsString("mockserver -p 1080"));
            assertThat("error should not use the java -jar form when launched via a launcher",
                err, not(containsString("java -jar")));
        } finally {
            System.clearProperty("mockserver.launcherName");
            Main.systemOut = originalOut;
            Main.systemErr = originalErr;
            if (originalSysProp != null) {
                System.setProperty("mockserver.serverPort", originalSysProp);
            }
            if (originalProp != null) {
                ConfigurationProperties.PROPERTIES.put("mockserver.serverPort", originalProp);
            }
        }
    }

    // ---- -D property passthrough ----

    @Test
    public void shouldApplyDSystemPropertyFromCommandLine() {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        String key = "mockserver.someUnusedTestProperty";
        String originalValue = System.getProperty(key);
        try {
            System.clearProperty(key);

            Main.main("run", "-p", String.valueOf(freePort), "-D" + key + "=hello");

            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("-D should set the system property", System.getProperty(key), is("hello"));
        } finally {
            if (originalValue != null) {
                System.setProperty(key, originalValue);
            } else {
                System.clearProperty(key);
            }
            stopQuietly(mockServerClient);
        }
    }

    // ---- ui subcommand ----

    @Test
    public void shouldListUiInTopLevelHelp() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("--help");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat(output, containsString("ui"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldStartUiAndPrintDashboardUrl() throws UnsupportedEncodingException {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        PrintStream originalOut = Main.systemOut;
        // Run headless so the dashboard URL is printed but no real browser is launched on the
        // developer's machine (this is exactly how `ui` degrades on a server/CI/SSH host).
        String originalHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("java.awt.headless", "true");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("ui", "-p", String.valueOf(freePort));

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("ui should print the dashboard URL",
                output, containsString("/mockserver/dashboard"));
            assertThat("ui should print the dashboard URL for the chosen port",
                output, containsString("localhost:" + freePort + "/mockserver/dashboard"));
        } finally {
            Main.systemOut = originalOut;
            if (originalHeadless != null) {
                System.setProperty("java.awt.headless", originalHeadless);
            } else {
                System.clearProperty("java.awt.headless");
            }
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldNotPrintDashboardUrlWhenUiFailsToStart() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        String originalHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("java.awt.headless", "true");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            // invalid port → run() reports a validation error and never starts the server
            Main.main("ui", "-p", "notaport");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("a failed start must not print a misleading dashboard URL",
                output, not(containsString("Dashboard UI")));
        } finally {
            Main.systemOut = originalOut;
            if (originalHeadless != null) {
                System.setProperty("java.awt.headless", originalHeadless);
            } else {
                System.clearProperty("java.awt.headless");
            }
        }
    }

    // ---- import subcommand ----

    @Test
    public void shouldNotPrependRunWhenImportSubcommandPresent() {
        String[] result = Main.preprocessArguments("import", "./expectations.json", "-p", "1080");
        assertThat(result[0], is("import"));
        assertThat(result.length, is(4));
    }

    @Test
    public void shouldListImportInTopLevelHelp() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("--help");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("top-level help should list the import subcommand",
                output, containsString("import"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldPrintHelpForImportSubcommand() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("help", "import");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("import help should mention the file parameter", output, containsString("file"));
            assertThat("import help should list the --port option", output, containsString("--port"));
            assertThat("import help should list the --host option", output, containsString("--host"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldImportExpectationsFromFileIntoRunningServer() throws IOException {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            // Start a server to import into
            Main.main("run", "-p", String.valueOf(freePort));
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));

            // Write an expectations array JSON file (the persisted/export format)
            File expectationsFile = tempFolder.newFile("import-expectations.json");
            String json = "[ {" +
                "\"httpRequest\": { \"path\": \"/imported_path\" }," +
                "\"httpResponse\": { \"body\": \"imported_body\" }" +
                "} ]";
            java.nio.file.Files.write(expectationsFile.toPath(), json.getBytes(StandardCharsets.UTF_8));

            // No expectations loaded yet
            assertThat("no expectations before import",
                mockServerClient.retrieveActiveExpectations(request().withPath("/imported_path")).length, is(0));

            // Import via the CLI subcommand
            Main.main("import", expectationsFile.getAbsolutePath(), "-p", String.valueOf(freePort));

            // The imported expectation is now active and responds
            assertThat("one expectation loaded after import",
                mockServerClient.retrieveActiveExpectations(request().withPath("/imported_path")).length, is(1));

            HttpResponse httpResponse = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
                .sendRequest(
                    request().withHeader(HOST.toString(), "127.0.0.1:" + freePort).withPath("/imported_path"),
                    10, TimeUnit.SECONDS
                );
            assertThat("imported expectation responds", httpResponse.getBodyAsString(), is("imported_body"));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldReturnNonZeroExitCodeWhenImportFails() throws UnsupportedEncodingException {
        PrintStream originalErr = Main.systemErr;
        PrintStream originalOut = Main.systemOut;
        final int freePort = PortFactory.findFreePort();
        try {
            Main.systemErr = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name());
            Main.systemOut = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name());

            // Drive the import command through picocli directly so we can observe the exit code that
            // Main.main() propagates to the JVM (an in-process Main.main(...) call cannot expose it).
            int exitCode = new picocli.CommandLine(new Main()).execute(
                "import", "/this/file/does/not/exist.json", "-p", String.valueOf(freePort));

            assertThat("a failed import must report a non-zero exit code", exitCode, is(1));
        } finally {
            Main.systemErr = originalErr;
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldReportErrorWhenImportFileMissing() throws UnsupportedEncodingException {
        PrintStream originalErr = Main.systemErr;
        PrintStream originalOut = Main.systemOut;
        final int freePort = PortFactory.findFreePort();
        try {
            ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
            Main.systemErr = new PrintStream(errBaos, true, StandardCharsets.UTF_8.name());
            Main.systemOut = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name());

            // No server running and a non-existent file → clean error, no legacy run-usage blob
            Main.main("import", "/this/file/does/not/exist.json", "-p", String.valueOf(freePort));

            String err = new String(errBaos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("import failure should report a clean error",
                err, containsString("could not import expectations"));
        } finally {
            Main.systemErr = originalErr;
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldNotApplyDevModeWithoutFlag() {
        // Verify that without --dev, the devMode property is false.
        // We cannot reliably assert exact maxLogEntries/maxExpectations values because
        // the property cache is shared across test methods; instead we verify the flag.
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);

        try {
            Main.main("run", "-p", String.valueOf(freePort));

            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("devMode should be false by default", ConfigurationProperties.devMode(), is(false));
        } finally {
            stopQuietly(mockServerClient);
        }
    }

    // ---- --watch live-reload flag ----

    @Test
    public void shouldListWatchInRunHelp() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("run", "--help");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("run help should list --watch", output, containsString("--watch"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldEnableWatchInitializationJsonViaWatchFlag() throws IOException {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        boolean originalWatch = ConfigurationProperties.watchInitializationJson();
        String originalInit = ConfigurationProperties.initializationJsonPath();
        File initFile = tempFolder.newFile("watch-init-flag.json");
        java.nio.file.Files.write(initFile.toPath(), "[]".getBytes(StandardCharsets.UTF_8));

        try {
            Main.main("run", "-p", String.valueOf(freePort),
                "--init", initFile.getAbsolutePath(), "--watch");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("--watch should enable watchInitializationJson",
                ConfigurationProperties.watchInitializationJson(), is(true));
        } finally {
            ConfigurationProperties.watchInitializationJson(originalWatch);
            ConfigurationProperties.initializationJsonPath(originalInit != null ? originalInit : "");
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldLiveReloadExpectationsWhenWatchedFileChanges() throws Exception {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        boolean originalWatch = ConfigurationProperties.watchInitializationJson();
        String originalInit = ConfigurationProperties.initializationJsonPath();
        // The poll period is now a Configuration property resolved from the static
        // ConfigurationProperties store on the Main.main() / MockServer startup path (there is no
        // per-instance route for the no-arg `new MockServer()` the CLI drives), so shorten it there.
        long originalPollPeriod = ConfigurationProperties.watchInitializationJsonPollPeriodMillis();
        ConfigurationProperties.watchInitializationJsonPollPeriodMillis(200);
        File initFile = tempFolder.newFile("watch-reload-init.json");
        java.nio.file.Files.write(initFile.toPath(), "[]".getBytes(StandardCharsets.UTF_8));

        try {
            Main.main("run", "-p", String.valueOf(freePort),
                "--init", initFile.getAbsolutePath(), "--watch");
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            assertThat("no expectation before the watched file is updated",
                mockServerClient.retrieveActiveExpectations(request().withPath("/watched")).length, is(0));

            // Modify the watched initializer file — the watcher should pick up the change and reload.
            String updated = "[ { \"httpRequest\": { \"path\": \"/watched\" }," +
                " \"httpResponse\": { \"body\": \"reloaded_body\" } } ]";
            java.nio.file.Files.write(initFile.toPath(), updated.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.SYNC);

            Retries.tryWaitForSuccess(() -> {
                try {
                    java.nio.file.Files.probeContentType(initFile.toPath());
                } catch (IOException ignore) {
                    // best-effort nudge for the filesystem to flush the change
                }
                assertThat("watched-file change should live-reload the expectation",
                    mockServerClient.retrieveActiveExpectations(request().withPath("/watched")).length, is(1));
            }, 50, 1000, MILLISECONDS);

            HttpResponse httpResponse = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
                .sendRequest(
                    request().withHeader(HOST.toString(), "127.0.0.1:" + freePort).withPath("/watched"),
                    10, TimeUnit.SECONDS
                );
            assertThat("reloaded expectation responds", httpResponse.getBodyAsString(), is("reloaded_body"));
        } finally {
            ConfigurationProperties.watchInitializationJson(originalWatch);
            ConfigurationProperties.initializationJsonPath(originalInit != null ? originalInit : "");
            ConfigurationProperties.watchInitializationJsonPollPeriodMillis(originalPollPeriod);
            stopQuietly(mockServerClient);
        }
    }

    // ---- demo subcommand ----

    @Test
    public void shouldNotPrependRunWhenDemoSubcommandPresent() {
        String[] result = Main.preprocessArguments("demo", "-p", "1080");
        assertThat(result[0], is("demo"));
        assertThat(result.length, is(3));
    }

    @Test
    public void shouldListDemoInTopLevelHelp() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("--help");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("top-level help should list the demo subcommand", output, containsString("demo"));
        } finally {
            Main.systemOut = originalOut;
        }
    }

    @Test
    public void shouldStartDemoSeedExamplesAndPrintInstructions() throws Exception {
        final int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("127.0.0.1", freePort);
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("demo", "-p", String.valueOf(freePort));

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("mockServerClient.hasStarted", mockServerClient.hasStarted(), is(true));
            // Instructions printed
            assertThat("demo prints a getting-started URL",
                output, containsString("localhost:" + freePort + "/hello"));
            assertThat("demo prints a sample curl",
                output, containsString("curl http://localhost:" + freePort + "/hello"));
            assertThat("demo prints the dashboard URL",
                output, containsString("/mockserver/dashboard"));

            // Example expectations seeded and serving
            HttpResponse hello = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
                .sendRequest(
                    request().withHeader(HOST.toString(), "127.0.0.1:" + freePort).withPath("/hello"),
                    10, TimeUnit.SECONDS
                );
            assertThat("demo /hello example responds",
                hello.getBodyAsString(), containsString("Hello from MockServer!"));

            HttpResponse user = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
                .sendRequest(
                    request().withHeader(HOST.toString(), "127.0.0.1:" + freePort).withPath("/users/1"),
                    10, TimeUnit.SECONDS
                );
            assertThat("demo /users/{id} example responds",
                user.getBodyAsString(), containsString("Example User"));
        } finally {
            Main.systemOut = originalOut;
            stopQuietly(mockServerClient);
        }
    }

    @Test
    public void shouldPrintHelpForDemoSubcommand() throws UnsupportedEncodingException {
        PrintStream originalOut = Main.systemOut;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Main.systemOut = new PrintStream(baos, true, StandardCharsets.UTF_8.name());

            Main.main("help", "demo");

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertThat("demo help should list the --port option", output, containsString("--port"));
            assertThat("demo help should describe the example expectations",
                output, containsString("example expectations"));
        } finally {
            Main.systemOut = originalOut;
        }
    }
}

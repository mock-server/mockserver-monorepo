package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.client.MockServerClient;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpTemplate.template;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end proof for GHSA-7pwj-xvc2-hfpc: a caller who can reach the control plane registers a
 * response template that tries to execute an OS command, and on a DEFAULT server the command must not
 * run.
 *
 * <p>This drives the reported attack shape rather than a unit of it — the expectation is registered
 * through the real management API and triggered by a real request over a socket, so it fails closed if
 * the sandbox is correct in {@code VelocityTemplateEngine} but is bypassed, reconfigured, or never
 * consulted on the live server path.</p>
 *
 * <p>The Velocity engine is used rather than JavaScript because Velocity ships in the DEFAULT
 * distribution: the GraalJS engine is an optional dependency present only in the {@code -graaljs} image
 * variant, so the Velocity reach-through ({@code $request.class.classLoader.loadClass(...)}) is the most
 * exposed form of this issue. The JavaScript equivalent is covered by
 * {@code JavaScriptTemplateEngineTest.shouldRefuseJavaClassesWithoutDeniedClassWithoutDeniedText}.</p>
 *
 * <p>Execution is asserted through a real side effect — the template shells out to create a marker file
 * — rather than through an error message, so "blocked" cannot be confused with "the payload was
 * malformed". The second test is the deliberate negative control: with the sandbox explicitly switched
 * off the SAME template DOES create the marker, which proves the payload is genuinely capable of
 * executing and that the first test's green comes from the sandbox and nothing else.</p>
 */
public class TemplateSandboxDefaultIntegrationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static MockServer defaultServer;
    private static MockServerClient defaultServerClient;
    private static MockServer unsandboxedServer;
    private static MockServerClient unsandboxedServerClient;
    private static NettyHttpClient httpClient;
    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void startServerAndClient() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(
            TemplateSandboxDefaultIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);

        // a server with the out-of-the-box configuration - nothing hardened by the test
        defaultServer = new MockServer();
        defaultServerClient = new MockServerClient("localhost", defaultServer.getLocalPort());

        // the negative control - an operator who has explicitly opted out of the template sandbox.
        // Configured per-server rather than through the global ConfigurationProperties so this test
        // cannot leak the insecure setting into any other test running in the same JVM.
        unsandboxedServer = new MockServer(configuration().velocityDisallowClassLoading(false));
        unsandboxedServerClient = new MockServerClient("localhost", unsandboxedServer.getLocalPort());
    }

    @AfterClass
    public static void stopServerAndClient() {
        stopQuietly(defaultServerClient);
        stopQuietly(unsandboxedServerClient);
        stopQuietly(defaultServer);
        stopQuietly(unsandboxedServer);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldNotExecuteOsCommandFromResponseTemplateOnDefaultServer() throws Exception {
        assumeShellAvailable();
        File marker = markerFile("default-server-marker");

        // given - an expectation registered through the control plane whose template shells out
        defaultServerClient
            .when(request().withPath("/rce-test"))
            .respond(template(HttpTemplate.TemplateType.VELOCITY, classLoadingTemplate(marker)));

        // when - the expectation is triggered by a real request
        HttpResponse response = send(defaultServer, "/rce-test");

        // then - the command never ran. Polled rather than checked once: Runtime.exec is asynchronous, so
        // a single immediate check passes even when the command HAS been spawned and is about to create
        // the marker. The negative control below creates its marker well inside this window.
        assertThat("template executed an OS command on a default server",
            waitForFile(marker, 2000), is(false));
        // and - the expectation still served its response; the blocked reference simply rendered nothing,
        // so no output of the command (a Process object) can appear in the body either
        assertThat("unexpected response: " + response, response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), isEmptyOrNullString());
    }

    @Test
    public void shouldExecuteOsCommandWhenSandboxExplicitlyDisabled() throws Exception {
        assumeShellAvailable();
        File marker = markerFile("unsandboxed-server-marker");

        // given - the SAME template on a server where the operator opted out of the sandbox
        unsandboxedServerClient
            .when(request().withPath("/rce-test"))
            .respond(template(HttpTemplate.TemplateType.VELOCITY, classLoadingTemplate(marker)));

        // when
        send(unsandboxedServer, "/rce-test");

        // then - the command DID run, proving the payload works and that the default server's green
        // above comes from the sandbox rather than from an inert payload
        assertThat("negative control did not execute - the payload no longer proves anything",
            waitForFile(marker, 10000), is(true));
    }

    /**
     * Reaches {@code java.lang.Runtime} the way Velocity allows when class loading is not sandboxed, and
     * runs a command whose only effect is to create {@code marker}. {@code $!} keeps the reference quiet
     * so a blocked lookup renders an empty body instead of leaking the reference text.
     */
    private String classLoadingTemplate(File marker) {
        return "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"$!request.class.classLoader.loadClass('java.lang.Runtime').getRuntime().exec('touch " + marker.getAbsolutePath() + "')\"" + NEW_LINE +
            "}";
    }

    private File markerFile(String name) {
        // deliberately NOT created - the assertions are about whether the template creates it
        return new File(temporaryFolder.getRoot(), name);
    }

    /** @return true if {@code file} exists within {@code timeoutMillis}; exec(...) is asynchronous. */
    private boolean waitForFile(File file, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!file.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        return file.exists();
    }

    private void assumeShellAvailable() {
        Assume.assumeFalse("test shells out, so it does not apply on Windows",
            System.getProperty("os.name", "").toLowerCase().startsWith("win"));
        Assume.assumeTrue("temporary folder path contains a space, which Runtime.exec(String) would split",
            !temporaryFolder.getRoot().getAbsolutePath().contains(" "));
    }

    private HttpResponse send(MockServer server, String path) throws Exception {
        return httpClient.sendRequest(
            request()
                .withMethod("GET")
                .withHeader(HOST.toString(), "localhost:" + server.getLocalPort())
                .withPath(path)
        ).get(20, TimeUnit.SECONDS);
    }

    private static final String NEW_LINE = System.lineSeparator();
}

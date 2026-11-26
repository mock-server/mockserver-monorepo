package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.test.Retries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Which files expectations are loaded from is decided at startup: {@code PUT /mockserver/configuration} cannot change
 * {@code initializationJsonPath} or {@code initializationOpenAPIPath} on a running server, so a watched reload still
 * reads the files the server started with. The servers started with the other files are the controls that prove those
 * files do load when they are given at startup.
 */
public class InitializationPathsStartupOnlyIntegrationTest {

    private static final long POLL_PERIOD_MILLIS = 200;

    private static Path directory;
    private static Path startupJson;
    private static Path otherJson;
    private static Path otherOpenAPI;
    private static MockServer watchedServer;
    private static MockServerClient watchedServerClient;
    private static MockServer otherJsonAtStartupServer;
    private static MockServer otherOpenAPIAtStartupServer;
    private static NettyHttpClient httpClient;
    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void startServersAndClient() throws IOException {
        directory = Files.createTempDirectory("initialization-paths-startup-only");
        startupJson = write("startup.json", expectationsFor("/from-startup-file"));
        otherJson = write("other.json", expectationsFor("/from-other-file"));
        otherOpenAPI = write("other-openapi.json", openAPIFor("/from-other-spec"));

        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(
            InitializationPathsStartupOnlyIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);

        watchedServer = new MockServer(configuration()
            .initializationJsonPath(startupJson.toString())
            .watchInitializationJson(true)
            .watchInitializationJsonPollPeriodMillis(POLL_PERIOD_MILLIS));
        watchedServerClient = new MockServerClient("localhost", watchedServer.getLocalPort());

        otherJsonAtStartupServer = new MockServer(configuration().initializationJsonPath(otherJson.toString()));
        otherOpenAPIAtStartupServer = new MockServer(configuration().initializationOpenAPIPath(otherOpenAPI.toString()));
    }

    @AfterClass
    public static void stopServersAndClient() throws IOException {
        stopQuietly(watchedServerClient);
        stopQuietly(watchedServer);
        stopQuietly(otherJsonAtStartupServer);
        stopQuietly(otherOpenAPIAtStartupServer);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
        for (Path file : new Path[]{startupJson, otherJson, otherOpenAPI, directory}) {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    public void shouldNotLetAConfigurationUpdateRepointTheWatchedInitializationFiles() throws Exception {
        // when
        String updated = watchedServerClient.updateConfiguration("{ "
            + "\"initializationJsonPath\": \"" + jsonPath(otherJson) + "\", "
            + "\"initializationOpenAPIPath\": \"" + jsonPath(otherOpenAPI) + "\" }");
        // and - the originally watched file changes, so the watcher reloads
        Files.write(startupJson, expectationsFor("/from-startup-file", "/from-startup-file-updated").getBytes(StandardCharsets.UTF_8));
        Retries.tryWaitForSuccess(() -> assertThat(send(watchedServer, "/from-startup-file-updated").getStatusCode(), is(200)), 100, 100, MILLISECONDS);

        // then - the reload read the startup file, not the files the PUT named
        assertThat(send(watchedServer, "/from-other-file").getStatusCode(), is(404));
        assertThat(send(watchedServer, "/from-other-spec").getStatusCode(), is(404));
        assertThat(updated, not(containsString(otherJson.getFileName().toString())));
        String retrieved = watchedServerClient.retrieveConfiguration();
        assertThat(retrieved, not(containsString(otherJson.getFileName().toString())));
        assertThat(retrieved, not(containsString(otherOpenAPI.getFileName().toString())));
        assertThat(retrieved, containsString(startupJson.getFileName().toString()));
    }

    @Test
    public void shouldLoadTheOtherFilesWhenTheyAreGivenAtStartup() throws Exception {
        // the same files do load when given at startup, so the test above is not inert
        assertThat(send(otherJsonAtStartupServer, "/from-other-file").getStatusCode(), is(200));
        assertThat(send(otherOpenAPIAtStartupServer, "/from-other-spec").getStatusCode(), is(200));
    }

    private static HttpResponse send(MockServer server, String path) throws Exception {
        return httpClient.sendRequest(
            request()
                .withHeader(HOST.toString(), "localhost:" + server.getLocalPort())
                .withPath(path)
        ).get(20, TimeUnit.SECONDS);
    }

    private static Path write(String name, String contents) throws IOException {
        return Files.write(directory.resolve(name), contents.getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static String expectationsFor(String... paths) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < paths.length; i++) {
            json.append(i == 0 ? "" : ",")
                .append("{ \"httpRequest\": { \"path\": \"").append(paths[i]).append("\" }, ")
                .append("\"httpResponse\": { \"statusCode\": 200 } }");
        }
        return json.append("]").toString();
    }

    private static String openAPIFor(String path) {
        return "{ \"openapi\": \"3.0.0\", \"info\": { \"title\": \"other\", \"version\": \"1\" }, \"paths\": { \""
            + path + "\": { \"get\": { \"operationId\": \"other\", \"responses\": { \"200\": { \"description\": \"ok\" } } } } } }";
    }
}

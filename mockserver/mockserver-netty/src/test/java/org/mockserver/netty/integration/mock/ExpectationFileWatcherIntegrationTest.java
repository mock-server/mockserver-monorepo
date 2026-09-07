package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.netty.MockServer;
import org.mockserver.persistence.FileWatcher;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.test.Retries;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * @author jamesdbloom
 */
public class ExpectationFileWatcherIntegrationTest {

    private static NettyHttpClient httpClient;
    private static EventLoopGroup clientEventLoopGroup;
    private static final MockServerLogger mockServerLogger = new MockServerLogger();

    /**
     * Short poll period (in milliseconds) so the watchers created by the MockServer under test (and
     * the persistence watcher built directly below) detect file changes within the assertion budget.
     * The poll period is now a Configuration property resolved from the static ConfigurationProperties
     * store on the {@code new MockServer()} startup path — there is no per-instance route for the
     * no-arg MockServer these tests drive — so it is shortened there and restored after the class.
     */
    private static final long POLL_PERIOD_MILLIS = 500;
    private static long originalPollPeriod;

    @BeforeClass
    public static void createClientAndEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(ExpectationFileSystemPersistenceIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);
        originalPollPeriod = ConfigurationProperties.watchInitializationJsonPollPeriodMillis();
        ConfigurationProperties.watchInitializationJsonPollPeriodMillis(POLL_PERIOD_MILLIS);
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        ConfigurationProperties.watchInitializationJsonPollPeriodMillis(originalPollPeriod);
    }

    @Test
    public void shouldDetectModifiedInitialiserJsonOnAdd() throws Exception {
        String initializationJsonPath = ConfigurationProperties.initializationJsonPath();
        ConfigurationProperties.watchInitializationJson(true);
        String persistedExpectationsPath = ConfigurationProperties.persistedExpectationsPath();
        ConfigurationProperties.persistExpectations(true);
        MockServer mockServer = null;
        try {
            // given - configuration
            File mockserverInitialization = File.createTempFile("mockserverInitialization", ".json");
            ConfigurationProperties.initializationJsonPath(mockserverInitialization.getAbsolutePath());
            File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
            ConfigurationProperties.persistedExpectationsPath(persistedExpectations.getAbsolutePath());
            // and - mockserver
            mockServer = new MockServer();
            MILLISECONDS.sleep(1000);
            // and - file watcher to detect persistence file being updated
            CompletableFuture<String> persistedExpectationsContents = new CompletableFuture<>();
            new FileWatcher(
                persistedExpectations.toPath(),
                () -> {
                    try {
                        persistedExpectationsContents.complete(new String(Files.readAllBytes(persistedExpectations.toPath()), StandardCharsets.UTF_8));
                    } catch (Throwable throwable) {
                        persistedExpectationsContents.completeExceptionally(throwable);
                    }
                },
                persistedExpectationsContents::completeExceptionally,
                mockServerLogger,
                POLL_PERIOD_MILLIS);
            MILLISECONDS.sleep(2000);

            // when
            String watchedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            write(mockserverInitialization.toPath(), watchedFileContents.getBytes(StandardCharsets.UTF_8));

            // then
            assertThat(persistedExpectations.getAbsolutePath() + " does not match expected content", persistedExpectationsContents.get(60, SECONDS), is(watchedFileContents));
        } finally {
            ConfigurationProperties.initializationJsonPath(initializationJsonPath);
            ConfigurationProperties.watchInitializationJson(false);
            ConfigurationProperties.persistedExpectationsPath(persistedExpectationsPath);
            ConfigurationProperties.persistExpectations(false);
            stopQuietly(mockServer);
        }
    }

    /**
     * 1 - starts with expectations added via API
     * 2 - add additional expectation via file watcher and updates source for existing
     * 3 - remove expectations via file watcher
     */
    @Test
    public void shouldDetectModifiedInitialiserJsonOnCompleteDeletion() throws Exception {
        String initializationJsonPath = ConfigurationProperties.initializationJsonPath();
        ConfigurationProperties.watchInitializationJson(true);
        String persistedExpectationsPath = ConfigurationProperties.persistedExpectationsPath();
        ConfigurationProperties.persistExpectations(true);
        MockServer mockServer = null;
        try {
            // given - configuration
            File mockserverInitialization = File.createTempFile("mockserverInitialization", ".json");
            ConfigurationProperties.initializationJsonPath(mockserverInitialization.getAbsolutePath());
            ConfigurationProperties.watchInitializationJson(true);
            File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
            ConfigurationProperties.persistedExpectationsPath(persistedExpectations.getAbsolutePath());

            // and - mockserver
            mockServer = new MockServer();
            MockServerClient mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());

            // and - matching existing expectations
            Expectation[] expectations = {
                new Expectation(
                    request()
                        .withPath("/simpleFirst")
                )
                    .withId("one")
                    .thenRespond(
                    response()
                        .withBody("some first response")
                ),
                new Expectation(
                    request()
                        .withPath("/simpleSecond")
                )
                    .withId("two")
                    .thenRespond(
                    response()
                        .withBody("some second response")
                )
            };
            httpClient
                .sendRequest(
                    request()
                        .withMethod("PUT")
                        .withHeader(HOST.toString(), "localhost:" + mockServer.getLocalPort())
                        .withPath("/mockserver/expectation")
                        .withBody(new ExpectationSerializer(mockServerLogger).serialize(expectations))
                )
                .get(10, TimeUnit.SECONDS);
            Retries.tryWaitForSuccess(() ->
                    assertThat("Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)), mockServerClient.retrieveActiveExpectations(null).length, equalTo(2)),
                10, 1000, MILLISECONDS
            );

            // when - update file
            String firstUpdatedWatchedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            write(mockserverInitialization.toPath(), firstUpdatedWatchedFileContents.getBytes(StandardCharsets.UTF_8));

            // then
            Retries.tryWaitForSuccess(() -> {
                    encourageFileSystemToNoticeChange(mockserverInitialization);
                    assertThat("Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)), mockServerClient.retrieveActiveExpectations(null).length, equalTo(3));
                },
                50, 1500, MILLISECONDS
            );

            // when - updated again
            String secondUpdatedWatchedFileContents = "[]";
            write(mockserverInitialization.toPath(), secondUpdatedWatchedFileContents.getBytes(StandardCharsets.UTF_8));

            // then
            Retries.tryWaitForSuccess(() -> {
                encourageFileSystemToNoticeChange(mockserverInitialization);
                assertThat("Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)), mockServerClient.retrieveActiveExpectations(null).length, equalTo(0));
                assertThat(persistedExpectations.getAbsolutePath() + " does not match expected content", readFileContents(persistedExpectations), is(secondUpdatedWatchedFileContents));
            }, 50, 1500, MILLISECONDS);
        } finally {
            ConfigurationProperties.initializationJsonPath(initializationJsonPath);
            ConfigurationProperties.watchInitializationJson(false);
            ConfigurationProperties.persistedExpectationsPath(persistedExpectationsPath);
            ConfigurationProperties.persistExpectations(false);
            stopQuietly(mockServer);
        }
    }

    /**
     * 1 - starts with expectations added via API
     * 2 - add additional expectation via file watcher and updates source for existing
     * 3 - remove expectations via file watcher
     */
    @Test
    public void shouldDetectModifiedInitialiserJsonOnPartialDeletion() throws Exception {
        String initializationJsonPath = ConfigurationProperties.initializationJsonPath();
        ConfigurationProperties.watchInitializationJson(true);
        String persistedExpectationsPath = ConfigurationProperties.persistedExpectationsPath();
        ConfigurationProperties.persistExpectations(true);
        MockServer mockServer = null;
        try {
            // given - configuration
            File mockserverInitialization = File.createTempFile("mockserverInitialization", ".json");
            ConfigurationProperties.initializationJsonPath(mockserverInitialization.getAbsolutePath());
            ConfigurationProperties.watchInitializationJson(true);
            File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
            ConfigurationProperties.persistedExpectationsPath(persistedExpectations.getAbsolutePath());

            // and - mockserver
            mockServer = new MockServer();
            MockServerClient mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());

            // and - matching existing expectations
            Expectation[] expectations = {
                new Expectation(
                    request()
                        .withPath("/simpleFirst")
                )
                    .withId("one")
                    .thenRespond(
                    response()
                        .withBody("some first response")
                ),
                new Expectation(
                    request()
                        .withPath("/simpleSecond")
                )
                    .withId("two")
                    .thenRespond(
                    response()
                        .withBody("some second response")
                )
            };
            httpClient
                .sendRequest(
                    request()
                        .withMethod("PUT")
                        .withHeader(HOST.toString(), "localhost:" + mockServer.getLocalPort())
                        .withPath("/mockserver/expectation")
                        .withBody(new ExpectationSerializer(mockServerLogger).serialize(expectations))
                )
                .get(10, TimeUnit.SECONDS);
            Retries.tryWaitForSuccess(() ->
                    assertThat("Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)), mockServerClient.retrieveActiveExpectations(null).length, equalTo(2)),
                10, 1000, MILLISECONDS
            );

            // when - update file
            String firstUpdatedWatchedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            write(mockserverInitialization.toPath(), firstUpdatedWatchedFileContents.getBytes(StandardCharsets.UTF_8));

            // then
            Retries.tryWaitForSuccess(() -> {
                    encourageFileSystemToNoticeChange(mockserverInitialization);
                    assertThat("Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)), mockServerClient.retrieveActiveExpectations(null).length, equalTo(3));
                },
                50, 1500, MILLISECONDS
            );

            // when - updated again
            String secondUpdatedWatchedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            write(mockserverInitialization.toPath(), secondUpdatedWatchedFileContents.getBytes(StandardCharsets.UTF_8));

            // then
            Retries.tryWaitForSuccess(() -> {
                encourageFileSystemToNoticeChange(mockserverInitialization);
                assertThat("Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)), mockServerClient.retrieveActiveExpectations(null).length, equalTo(1));
                assertThat(persistedExpectations.getAbsolutePath() + " does not match expected content", readFileContents(persistedExpectations), equalTo(secondUpdatedWatchedFileContents));
            }, 50, 1500, MILLISECONDS);
        } finally {
            ConfigurationProperties.initializationJsonPath(initializationJsonPath);
            ConfigurationProperties.watchInitializationJson(false);
            ConfigurationProperties.persistedExpectationsPath(persistedExpectationsPath);
            ConfigurationProperties.persistExpectations(false);
            stopQuietly(mockServer);
        }
    }

    /**
     * 1 - starts with expectations added via API
     * 2 - add additional expectation via file watcher and updates source for existing
     * 3 - update expectations via file watcher
     */
    @Test
    public void shouldDetectModifiedInitialiserJsonOnUpdate() throws Exception {
        String initializationJsonPath = ConfigurationProperties.initializationJsonPath();
        ConfigurationProperties.watchInitializationJson(true);
        String persistedExpectationsPath = ConfigurationProperties.persistedExpectationsPath();
        ConfigurationProperties.persistExpectations(true);
        MockServer mockServer = null;
        try {
            // given - configuration
            File mockserverInitialization = File.createTempFile("mockserverInitialization", ".json");
            ConfigurationProperties.initializationJsonPath(mockserverInitialization.getAbsolutePath());
            ConfigurationProperties.watchInitializationJson(true);
            File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
            ConfigurationProperties.persistedExpectationsPath(persistedExpectations.getAbsolutePath());

            // and - mockserver
            mockServer = new MockServer();
            MockServerClient mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());

            // and - matching existing expectations
            Expectation[] expectations = {
                new Expectation(
                    request()
                        .withPath("/simpleFirst")
                )
                    .withId("one")
                    .thenRespond(
                    response()
                        .withBody("some first response")
                ),
                new Expectation(
                    request()
                        .withPath("/simpleSecond")
                )
                    .withId("two")
                    .thenRespond(
                    response()
                        .withBody("some second response")
                )
            };
            httpClient
                .sendRequest(
                    request()
                        .withMethod("PUT")
                        .withHeader(HOST.toString(), "localhost:" + mockServer.getLocalPort())
                        .withPath("/mockserver/expectation")
                        .withBody(new ExpectationSerializer(mockServerLogger).serialize(expectations))
                )
                .get(10, TimeUnit.SECONDS);
            Retries.tryWaitForSuccess(() ->
                    assertThat("Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)), mockServerClient.retrieveActiveExpectations(null).length, equalTo(2)),
                10, 1000, MILLISECONDS
            );

            // when - update file
            String firstUpdatedWatchedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            write(mockserverInitialization.toPath(), firstUpdatedWatchedFileContents.getBytes(StandardCharsets.UTF_8));

            // then
            Retries.tryWaitForSuccess(() -> {
                    encourageFileSystemToNoticeChange(mockserverInitialization);
                    assertThat("Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)), mockServerClient.retrieveActiveExpectations(null).length, equalTo(3));
                },
                50, 1500, MILLISECONDS
            );

            // when - updated again
            String secondUpdatedWatchedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecondUpdated\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second updated response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFourth\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some fourth response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"four\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            write(mockserverInitialization.toPath(), secondUpdatedWatchedFileContents.getBytes(StandardCharsets.UTF_8));

            // then
            Retries.tryWaitForSuccess(() -> {
                encourageFileSystemToNoticeChange(mockserverInitialization);
                assertThat(persistedExpectations.getAbsolutePath() + " does not match expected content", readFileContents(persistedExpectations), is(secondUpdatedWatchedFileContents));
            }, 50, 1500, MILLISECONDS);
            assertThat("Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)), mockServerClient.retrieveActiveExpectations(null).length, equalTo(3));
        } finally {
            ConfigurationProperties.initializationJsonPath(initializationJsonPath);
            ConfigurationProperties.watchInitializationJson(false);
            ConfigurationProperties.persistedExpectationsPath(persistedExpectationsPath);
            ConfigurationProperties.persistExpectations(false);
            stopQuietly(mockServer);
        }
    }

    /**
     * 1 - starts with expectations added via file
     * 2 - update two (of three) expectations via API
     */
    @Test
    public void shouldNotDeleteInitialisedOnAPIUpdate() throws Exception {
        String initializationJsonPath = ConfigurationProperties.initializationJsonPath();
        ConfigurationProperties.watchInitializationJson(true);
        String persistedExpectationsPath = ConfigurationProperties.persistedExpectationsPath();
        ConfigurationProperties.persistExpectations(true);
        MockServer mockServer = null;
        try {
            // given - configuration
            File mockserverInitialization = File.createTempFile("mockserverInitialization", ".json");
            ConfigurationProperties.initializationJsonPath(mockserverInitialization.getAbsolutePath());
            ConfigurationProperties.watchInitializationJson(true);
            File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
            ConfigurationProperties.persistedExpectationsPath(persistedExpectations.getAbsolutePath());

            // and - mockserver
            mockServer = new MockServer();
            MockServerClient mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());

            // when - update file
            String firstUpdatedWatchedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            write(mockserverInitialization.toPath(), firstUpdatedWatchedFileContents.getBytes(StandardCharsets.UTF_8));

            // then
            Retries.tryWaitForSuccess(() -> {
                    encourageFileSystemToNoticeChange(mockserverInitialization);
                    assertThat("Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)), mockServerClient.retrieveActiveExpectations(null).length, equalTo(3));
                },
                50, 1500, MILLISECONDS
            );

            // then - update via API
            Expectation[] expectations = {
                new Expectation(
                    request()
                        .withPath("/simpleFirst")
                )
                    .withId("one")
                    .thenRespond(
                    response()
                        .withBody("some first updated response")
                ),
                new Expectation(
                    request()
                        .withPath("/simpleSecond")
                )
                    .withId("two")
                    .thenRespond(
                    response()
                        .withBody("some second updated response")
                )
            };
            httpClient
                .sendRequest(
                    request()
                        .withMethod("PUT")
                        .withHeader(HOST.toString(), "localhost:" + mockServer.getLocalPort())
                        .withPath("/mockserver/expectation")
                        .withBody(new ExpectationSerializer(mockServerLogger).serialize(expectations))
                )
                .get(10, TimeUnit.SECONDS);
            Retries.tryWaitForSuccess(() ->
                    assertThat(
                        "Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)),
                        Arrays.asList(mockServerClient.retrieveActiveExpectations(null)),
                        containsInAnyOrder(
                            expectations[0],
                            expectations[1],
                            new Expectation(
                                request()
                                    .withPath("/simpleThird")
                            )
                                .withId("three")
                                .thenRespond(
                                    response()
                                        .withBody("some third response")
                                )
                        )
                    ),
                10, 1000, MILLISECONDS
            );

            // when - updated again
            String apiUpdatedPersistedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first updated response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second updated response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";

            // then
            Retries.tryWaitForSuccess(() -> {
                encourageFileSystemToNoticeChange(mockserverInitialization);
                String actual = readFileContents(persistedExpectations);
                assertThat(persistedExpectations.getAbsolutePath() + " does not match expected content", actual, is(apiUpdatedPersistedFileContents));
            }, 50, 1500, MILLISECONDS);
            assertThat("Found: " + Arrays.asList(mockServerClient.retrieveActiveExpectations(null)), mockServerClient.retrieveActiveExpectations(null).length, equalTo(3));
        } finally {
            ConfigurationProperties.initializationJsonPath(initializationJsonPath);
            ConfigurationProperties.watchInitializationJson(false);
            ConfigurationProperties.persistedExpectationsPath(persistedExpectationsPath);
            ConfigurationProperties.persistExpectations(false);
            stopQuietly(mockServer);
        }
    }

    public static Path write(Path path, byte[] bytes) throws IOException {
        if (bytes != null) {
            try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC)) {
                out.write(bytes, 0, bytes.length);
                out.flush();
            }
        }
        return path;
    }

    private String readFileContents(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void encourageFileSystemToNoticeChange(File file) {
        try {
            Files.probeContentType(file.toPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        readFileContents(file);
    }

}
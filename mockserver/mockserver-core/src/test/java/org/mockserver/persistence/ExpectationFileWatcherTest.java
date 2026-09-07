package org.mockserver.persistence;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.server.initialize.ExpectationInitializerLoader;
import org.mockserver.test.Retries;
import org.mockserver.uuid.UUIDService;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.collection.IsEmptyCollection.emptyCollectionOf;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class ExpectationFileWatcherTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final ExpectationSerializer expectationSerializer = new ExpectationSerializer(new MockServerLogger());
    private MockServerLogger mockServerLogger;
    private RequestMatchers requestMatchers;

    /**
     * Short poll period (in milliseconds) applied per-test to the {@link Configuration} instance, so
     * the file watchers this test creates detect changes within its assertion budget. The poll period
     * is a per-instance configuration property rather than shared mutable static state, so this test
     * mutates no JVM-global state and cannot race any other watcher test on it.
     */
    private static final long POLL_PERIOD_MILLIS = 500;

    @Before
    public void createMockServerMatcher() {
        Configuration matcherConfiguration = configuration();
        mockServerLogger = new MockServerLogger(matcherConfiguration, ExpectationFileWatcherTest.class);
        requestMatchers = new RequestMatchers(matcherConfiguration, mockServerLogger, new Scheduler(matcherConfiguration, mockServerLogger), new WebSocketClientRegistry(matcherConfiguration, mockServerLogger));
    }

    @Test
    public void shouldDetectModifiedInitialiserJsonInWorkingDirectoryThatDoesNotInitiallyExist() throws Exception {
        ExpectationFileWatcher expectationFileWatcher = null;
        try {
            // given - configuration (TemporaryFolder deletes the file after the test, so no deleteOnExit needed)
            File mockserverInitialization = new File(temporaryFolder.getRoot(), "mockserverInitialization" + UUIDService.getUUID() + ".json");
            Configuration configuration = configuration()
                .initializationJsonPath(mockserverInitialization.getPath())
                .watchInitializationJson(true)
                .watchInitializationJsonPollPeriodMillis(POLL_PERIOD_MILLIS);
            MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileWatcherTest.class);
            // and - expectation update notification
            CompletableFuture<String> expectationsUpdated = new CompletableFuture<>();
            requestMatchers.registerListener((requestMatchers, cause) -> expectationsUpdated.complete("updated"));
            // and - file watcher
            expectationFileWatcher = new ExpectationFileWatcher(configuration, logger, requestMatchers, new ExpectationInitializerLoader(configuration, logger, requestMatchers));
            assertThat(mockserverInitialization.exists(), equalTo(false));

            // when
            String watchedFileContents = "[ {" + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            Files.write(mockserverInitialization.toPath(), watchedFileContents.getBytes(StandardCharsets.UTF_8));
            long updatedFileTime = System.currentTimeMillis();

            expectationsUpdated.get(30, SECONDS);
            System.out.println("update processed in: " + (System.currentTimeMillis() - updatedFileTime) + "ms");

            // then
            List<Expectation> expectations = requestMatchers.retrieveActiveExpectations(null);
            assertThat(
                expectations,
                contains(
                    new Expectation(
                        request()
                            .withPath("/simpleFirst")
                    )
                        .withId("one")
                        .thenRespond(
                            response()
                                .withBody("some first response")
                        )
                    ,
                    new Expectation(
                        request()
                            .withPath("/simpleSecond")
                    )
                        .withId("two")
                        .thenRespond(
                            response()
                                .withBody("some second response")
                        ),
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
            );
        } finally {
            if (expectationFileWatcher != null) {
                expectationFileWatcher.stop();
            }
        }
    }

    @Test
    public void shouldDetectModifiedInitialiserJsonOnAdd() throws Exception {
        ExpectationFileWatcher expectationFileWatcher = null;
        try {
            // given - configuration
            File mockserverInitialization = File.createTempFile("mockserverInitialization", ".json");
            Configuration configuration = configuration()
                .initializationJsonPath(mockserverInitialization.getAbsolutePath())
                .watchInitializationJson(true)
                .watchInitializationJsonPollPeriodMillis(POLL_PERIOD_MILLIS);
            MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileWatcherTest.class);
            // and - expectation update notification
            CompletableFuture<String> expectationsUpdated = new CompletableFuture<>();
            requestMatchers.registerListener((requestMatchers, cause) -> expectationsUpdated.complete("updated"));
            // and - file watcher
            expectationFileWatcher = new ExpectationFileWatcher(configuration, logger, requestMatchers, new ExpectationInitializerLoader(configuration, logger, requestMatchers));

            // when
            String watchedFileContents = "[ {" + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            Files.write(mockserverInitialization.toPath(), watchedFileContents.getBytes(StandardCharsets.UTF_8));
            long updatedFileTime = System.currentTimeMillis();

            expectationsUpdated.get(30, SECONDS);
            System.out.println("update processed in: " + (System.currentTimeMillis() - updatedFileTime) + "ms");

            // then
            List<Expectation> expectations = requestMatchers.retrieveActiveExpectations(null);
            assertThat(
                expectations,
                contains(
                    new Expectation(
                        request()
                            .withPath("/simpleFirst")
                    )
                        .withId("one")
                        .thenRespond(
                            response()
                                .withBody("some first response")
                        )
                    ,
                    new Expectation(
                        request()
                            .withPath("/simpleSecond")
                    )
                        .withId("two")
                        .thenRespond(
                            response()
                                .withBody("some second response")
                        ),
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
            );
        } finally {
            if (expectationFileWatcher != null) {
                expectationFileWatcher.stop();
            }
        }
    }

    @Test
    public void shouldDetectModifiedInitialiserJsonOnAddWithGlob() throws Exception {
        ExpectationFileWatcher expectationFileWatcher = null;
        try {
            // given - configuration
            String uniquePrefix = UUID.randomUUID().toString();
            File mockserverInitializer = File.createTempFile(uniquePrefix + "_mockserverInitialization", ".json");
            File mockserverInitializerOne = File.createTempFile(uniquePrefix + "_mockserverInitializationOne", ".json");
            File mockserverInitializerTwo = File.createTempFile(uniquePrefix + "_mockserverInitializationTwo", ".json");
            File mockserverInitializerThree = File.createTempFile(uniquePrefix + "_mockserverInitializationThree", ".json");
            Configuration configuration = configuration()
                .initializationJsonPath(mockserverInitializer.getParentFile().getAbsolutePath() + "/" + uniquePrefix + "_mockserverInitialization{One,Two}*.json")
                .watchInitializationJson(true)
                .watchInitializationJsonPollPeriodMillis(POLL_PERIOD_MILLIS);
            MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileWatcherTest.class);
            // and - file watcher
            expectationFileWatcher = new ExpectationFileWatcher(configuration, logger, requestMatchers, new ExpectationInitializerLoader(configuration, logger, requestMatchers));

            // when
            Expectation[] expectations = {
                new Expectation(
                    request()
                        .withPath("/simpleFirst")
                )
                    .thenRespond(
                    response()
                        .withBody("some first response")
                ),
                new Expectation(
                    request()
                        .withPath("/simpleSecond")
                )
                    .thenRespond(
                    response()
                        .withBody("some second response")
                )
            };
            Files.write(mockserverInitializer.toPath(), expectationSerializer.serialize(expectations).getBytes(StandardCharsets.UTF_8));
            Expectation[] expectationsOne = {
                new Expectation(
                    request()
                        .withPath("/pathOneFirst")
                )
                    .thenRespond(
                    response()
                        .withBody("one first response")
                ),
                new Expectation(
                    request()
                        .withPath("/pathOneSecond")
                )
                    .thenRespond(
                    response()
                        .withBody("one second response")
                )
            };
            Files.write(mockserverInitializerOne.toPath(), expectationSerializer.serialize(expectationsOne).getBytes(StandardCharsets.UTF_8));
            Expectation[] expectationsTwo = {
                new Expectation(
                    request()
                        .withPath("/pathTwoFirst")
                )
                    .thenRespond(
                    response()
                        .withBody("two first response")
                ),
                new Expectation(
                    request()
                        .withPath("/pathTwoSecond")
                )
                    .thenRespond(
                    response()
                        .withBody("two second response")
                )
            };
            Files.write(mockserverInitializerTwo.toPath(), expectationSerializer.serialize(expectationsTwo).getBytes(StandardCharsets.UTF_8));
            Expectation[] expectationsThree = {
                new Expectation(
                    request()
                        .withPath("/pathThreeFirst")
                )
                    .thenRespond(
                    response()
                        .withBody("three first response")
                ),
                new Expectation(
                    request()
                        .withPath("/pathThreeSecond")
                )
                    .thenRespond(
                    response()
                        .withBody("three second response")
                )
            };
            Files.write(mockserverInitializerThree.toPath(), expectationSerializer.serialize(expectationsThree).getBytes(StandardCharsets.UTF_8));
            long updatedFileTime = System.currentTimeMillis();

            // then - wait for all expectations to be loaded. Poll the *complete* assertion
            // (size AND contents) with a generous timeout: a background watcher poll reloads
            // per file by clearing then re-adding, so a read taken mid-reload can transiently
            // observe fewer than 4 expectations. Asserting inside the retry loop means any such
            // transient state simply retries instead of failing, and the steady state between
            // reloads (all 4 present) is what ultimately satisfies the assertion.
            Retries.tryWaitForSuccess(() -> {
                List<Expectation> expectationsList = requestMatchers.retrieveActiveExpectations(null);
                assertThat(expectationsList.size(), equalTo(4));
                assertThat(expectationsList, containsInAnyOrder(
                new Expectation(
                    request()
                        .withPath("/pathOneFirst")
                )
                    .thenRespond(
                        response()
                            .withBody("one first response")
                    ),
                new Expectation(
                    request()
                        .withPath("/pathOneSecond")
                )
                    .thenRespond(
                        response()
                            .withBody("one second response")
                    ),
                new Expectation(
                    request()
                        .withPath("/pathTwoFirst")
                )
                    .thenRespond(
                        response()
                            .withBody("two first response")
                    ),
                new Expectation(
                    request()
                        .withPath("/pathTwoSecond")
                )
                    .thenRespond(
                        response()
                            .withBody("two second response")
                    )
                ));
            }, 300, 100, MILLISECONDS);
            System.out.println("update processed in: " + (System.currentTimeMillis() - updatedFileTime) + "ms");
        } finally {
            if (expectationFileWatcher != null) {
                expectationFileWatcher.stop();
            }
        }
    }

    @Test
    public void shouldDetectModifiedInitialiserJsonOnPartialDeletion() throws Exception {
        ExpectationFileWatcher expectationFileWatcher = null;
        try {
            // given - configuration
            File mockserverInitialization = File.createTempFile("mockserverInitialization", ".json");
            Configuration configuration = configuration()
                .initializationJsonPath(mockserverInitialization.getAbsolutePath())
                .watchInitializationJson(true)
                .watchInitializationJsonPollPeriodMillis(POLL_PERIOD_MILLIS);
            MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileWatcherTest.class);
            // and - existing file contents
            String watchedFileContents = "[ {" + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            Files.write(mockserverInitialization.toPath(), watchedFileContents.getBytes(StandardCharsets.UTF_8));
            // and - matching existing expectations
            requestMatchers.update(new Expectation[]{
                new Expectation(
                    request()
                        .withPath("/simpleFirst")
                )
                    .withId("one")
                    .thenRespond(
                    response()
                        .withBody("some first response")
                )
                ,
                new Expectation(
                    request()
                        .withPath("/simpleSecond")
                )
                    .withId("two")
                    .thenRespond(
                    response()
                        .withBody("some second response")
                ),
                new Expectation(
                    request()
                        .withPath("/simpleThird")
                )
                    .withId("three")
                    .thenRespond(
                    response()
                        .withBody("some third response")
                )
            }, new MockServerMatcherNotifier.Cause(mockserverInitialization.getAbsolutePath(), MockServerMatcherNotifier.Cause.Type.FILE_INITIALISER));
            // and - expectation update notification
            CompletableFuture<String> expectationsUpdated = new CompletableFuture<>();
            requestMatchers.registerListener((requestMatchers, cause) -> expectationsUpdated.complete("updated"));
            // and - file watcher
            expectationFileWatcher = new ExpectationFileWatcher(configuration, logger, requestMatchers, new ExpectationInitializerLoader(configuration, logger, requestMatchers));

            // when
            watchedFileContents = "[ {" + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            Files.write(mockserverInitialization.toPath(), watchedFileContents.getBytes(StandardCharsets.UTF_8));
            long updatedFileTime = System.currentTimeMillis();

            expectationsUpdated.get(30, SECONDS);
            System.out.println("update processed in: " + (System.currentTimeMillis() - updatedFileTime) + "ms");

            // then
            List<Expectation> expectations = requestMatchers.retrieveActiveExpectations(null);
            assertThat(
                expectations,
                contains(
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
                            .withPath("/simpleThird")
                    )
                        .withId("four")
                        .thenRespond(
                            response()
                                .withBody("some third response")
                        )
                )
            );
        } finally {
            if (expectationFileWatcher != null) {
                expectationFileWatcher.stop();
            }
        }
    }

    @Test
    public void shouldDetectModifiedInitialiserJsonOnCompleteDeletion() throws Exception {
        ExpectationFileWatcher expectationFileWatcher = null;
        try {
            // given - configuration
            File mockserverInitialization = File.createTempFile("mockserverInitialization", ".json");
            Configuration configuration = configuration()
                .initializationJsonPath(mockserverInitialization.getAbsolutePath())
                .watchInitializationJson(true)
                .watchInitializationJsonPollPeriodMillis(POLL_PERIOD_MILLIS);
            MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileWatcherTest.class);
            // and - existing file contents
            String watchedFileContents = "[ {" + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            Files.write(mockserverInitialization.toPath(), watchedFileContents.getBytes(StandardCharsets.UTF_8));
            // and - matching existing expectations
            requestMatchers.update(new Expectation[]{
                new Expectation(
                    request()
                        .withPath("/simpleFirst")
                )
                    .withId("one")
                    .thenRespond(
                    response()
                        .withBody("some first response")
                )
                ,
                new Expectation(
                    request()
                        .withPath("/simpleSecond")
                )
                    .withId("two")
                    .thenRespond(
                    response()
                        .withBody("some second response")
                ),
                new Expectation(
                    request()
                        .withPath("/simpleThird")
                )
                    .withId("three")
                    .thenRespond(
                    response()
                        .withBody("some third response")
                )
            }, new MockServerMatcherNotifier.Cause(mockserverInitialization.getAbsolutePath(), MockServerMatcherNotifier.Cause.Type.FILE_INITIALISER));
            // and - expectation update notification
            CompletableFuture<String> expectationsUpdated = new CompletableFuture<>();
            requestMatchers.registerListener((requestMatchers, cause) -> expectationsUpdated.complete("updated"));
            // and - file watcher
            expectationFileWatcher = new ExpectationFileWatcher(configuration, logger, requestMatchers, new ExpectationInitializerLoader(configuration, logger, requestMatchers));

            // when
            watchedFileContents = "[ ]";
            Files.write(mockserverInitialization.toPath(), watchedFileContents.getBytes(StandardCharsets.UTF_8));
            long updatedFileTime = System.currentTimeMillis();

            expectationsUpdated.get(30, SECONDS);
            System.out.println("update processed in: " + (System.currentTimeMillis() - updatedFileTime) + "ms");

            // then
            List<Expectation> expectations = requestMatchers.retrieveActiveExpectations(null);
            assertThat(
                expectations,
                emptyCollectionOf(Expectation.class)
            );
        } finally {
            if (expectationFileWatcher != null) {
                expectationFileWatcher.stop();
            }
        }
    }

    @Test
    public void shouldDetectModifiedInitialiserJsonOnUpdateFileChanged() throws Exception {
        ExpectationFileWatcher expectationFileWatcher = null;
        try {
            // given - configuration
            File mockserverInitialization = File.createTempFile("mockserverInitialization", ".json");
            Configuration configuration = configuration()
                .initializationJsonPath(mockserverInitialization.getAbsolutePath())
                .watchInitializationJson(true)
                .watchInitializationJsonPollPeriodMillis(POLL_PERIOD_MILLIS);
            MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileWatcherTest.class);
            // and - existing file contents
            String watchedFileContents = "[ {" + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            Files.write(mockserverInitialization.toPath(), watchedFileContents.getBytes(StandardCharsets.UTF_8));
            // and - expectation update notification
            CompletableFuture<String> expectationsInitialised = new CompletableFuture<>();
            requestMatchers.registerListener((requestMatchers, cause) -> expectationsInitialised.complete("updated"));
            // and - file watcher
            expectationFileWatcher = new ExpectationFileWatcher(configuration, logger, requestMatchers, new ExpectationInitializerLoader(configuration, logger, requestMatchers));
            expectationsInitialised.get(30, SECONDS);

            // when
            CompletableFuture<String> expectationsUpdated = new CompletableFuture<>();
            requestMatchers.registerListener((requestMatchers, cause) -> expectationsUpdated.complete("updated"));
            watchedFileContents = "[ {" + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecondUpdated\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second updated response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"id\" : \"four\"," + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFourth\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some fourth response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            Files.write(mockserverInitialization.toPath(), watchedFileContents.getBytes(StandardCharsets.UTF_8));
            long updatedFileTime = System.currentTimeMillis();

            expectationsUpdated.get(30, SECONDS);
            System.out.println("update processed in: " + (System.currentTimeMillis() - updatedFileTime) + "ms");

            // then
            List<Expectation> expectations = requestMatchers.retrieveActiveExpectations(null);
            assertThat(
                expectations,
                contains(
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
                            .withPath("/simpleSecondUpdated")
                    )
                        .withId("two")
                        .thenRespond(
                            response()
                                .withBody("some second updated response")
                        ),
                    new Expectation(
                        request()
                            .withPath("/simpleFourth")
                    )
                        .withId("four")
                        .thenRespond(
                            response()
                                .withBody("some fourth response")
                        )
                )
            );
        } finally {
            if (expectationFileWatcher != null) {
                expectationFileWatcher.stop();
            }
        }
    }

}

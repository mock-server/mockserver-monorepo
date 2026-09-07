package org.mockserver.persistence;

import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause;
import org.mockserver.server.initialize.ExpectationInitializerLoader;
import org.slf4j.event.Level;

import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.slf4j.event.Level.*;

/**
 * @author jamesdbloom
 */
public class ExpectationFileWatcher {

    private final Configuration configuration;
    private final ExpectationInitializerLoader expectationInitializerLoader;
    private final MockServerLogger mockServerLogger;
    private List<FileWatcher> fileWatchers;

    public ExpectationFileWatcher(Configuration configuration, MockServerLogger mockServerLogger, RequestMatchers requestMatchers, ExpectationInitializerLoader expectationInitializerLoader) {
        this.configuration = configuration;
        if (configuration.watchInitializationJson()) {
            this.mockServerLogger = mockServerLogger;
            this.expectationInitializerLoader = expectationInitializerLoader;
            List<String> initializationJsonPaths = ExpectationInitializerLoader.expandedInitializationJsonPaths(configuration.initializationJsonPath());
            List<String> initializationOpenAPIPaths = ExpectationInitializerLoader.expandedInitializationOpenAPIPaths(configuration.initializationOpenAPIPath());
            List<String> allPaths = new ArrayList<>();
            allPaths.addAll(initializationJsonPaths);
            allPaths.addAll(initializationOpenAPIPaths);
            long pollPeriodMillis = configuration.watchInitializationJsonPollPeriodMillis();
            try {
                fileWatchers = allPaths
                    .stream()
                    .map(initializationPath -> {
                        try {
                            return new FileWatcher(Paths.get(initializationPath), () -> {
                                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                                    mockServerLogger.logEvent(
                                        new LogEntry()
                                            .setLogLevel(DEBUG)
                                            .setMessageFormat("expectation file watcher updating expectations as modification detected on file{}")
                                            .setArguments(initializationPath)
                                    );
                                }
                                addExpectationsFromInitializer();
                            }, throwable -> {
                                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                                    mockServerLogger.logEvent(
                                        new LogEntry()
                                            .setLogLevel(WARN)
                                            .setMessageFormat("exception while processing expectation file update " + throwable.getMessage())
                                            .setThrowable(throwable)
                                    );
                                }
                            }, mockServerLogger, pollPeriodMillis);
                        } catch (Throwable throwable) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.ERROR)
                                    .setMessageFormat("exception creating file watcher for{}")
                                    .setArguments(initializationPath)
                                    .setThrowable(throwable)
                            );
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            } catch (Throwable throwable) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception creating file watchers for{}")
                        .setArguments(allPaths)
                        .setThrowable(throwable)
                );
            }
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(INFO)
                        .setMessageFormat("created expectation file watcher for{}")
                        .setArguments(allPaths)
                );
            }
        } else {
            this.mockServerLogger = null;
            this.expectationInitializerLoader = null;
        }
    }

    private synchronized void addExpectationsFromInitializer() {
        expectationInitializerLoader.retrieveExpectationsFromFile("", "exception while loading JSON initialization file with file watcher, ignoring file:{}", "updating expectations:{}from file:{}", Cause.Type.FILE_INITIALISER);
        expectationInitializerLoader.retrieveExpectationsFromOpenAPIFile("", "exception while loading OpenAPI initialization file with file watcher, ignoring file:{}", "updating expectations:{}from OpenAPI file:{}", Cause.Type.FILE_INITIALISER);
    }

    public void stop() {
        if (fileWatchers != null) {
            fileWatchers.forEach(fileWatcher -> fileWatcher.setRunning(false));
        }
    }
}

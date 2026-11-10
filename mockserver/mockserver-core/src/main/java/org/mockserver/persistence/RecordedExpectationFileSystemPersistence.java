package org.mockserver.persistence;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.listeners.MockServerLogListener;
import org.mockserver.serialization.SerializationFailure;
import org.mockserver.serialization.model.ExpectationDTO;
import org.mockserver.serialization.serializers.response.TimeToLiveDTOPersistenceSerializer;
import org.mockserver.state.BlobKeys;
import org.mockserver.state.BlobStore;
import org.slf4j.event.Level;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockserver.serialization.ObjectMapperFactory.createObjectMapper;
import static org.slf4j.event.Level.*;

public class RecordedExpectationFileSystemPersistence implements MockServerLogListener {

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final MockServerEventLog mockServerEventLog;
    private final ObjectWriter objectWriter;
    private final Path filePath;
    private final String blobKey;
    private final BlobStore blobStore;
    private final java.util.concurrent.locks.ReentrantLock writeOrderLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Creates persistence backed by the given {@link BlobStore}. The blob key is
     * derived by {@link org.mockserver.state.BlobKeys#forPersistedFile(BlobStore, Path)}:
     * the absolute path of {@code configuration.persistedRecordedExpectationsPath()} for the
     * {@link org.mockserver.state.FilesystemBlobStore}, so it writes the exact same file as
     * the previous direct-I/O implementation, and the FILE NAME alone for every other store,
     * because an absolute local path is not a valid object-store key.
     *
     * @param configuration    the MockServer configuration
     * @param mockServerLogger logger for diagnostics
     * @param mockServerEventLog the event log to observe for recorded expectations
     * @param blobStore        the blob store to delegate writes to
     */
    public RecordedExpectationFileSystemPersistence(Configuration configuration, MockServerLogger mockServerLogger, MockServerEventLog mockServerEventLog, BlobStore blobStore) {
        this.configuration = configuration;
        if (configuration.persistRecordedExpectations()) {
            this.mockServerLogger = mockServerLogger;
            this.mockServerEventLog = mockServerEventLog;
            this.objectWriter = createObjectMapper(true, false, new TimeToLiveDTOPersistenceSerializer());
            this.filePath = Paths.get(configuration.persistedRecordedExpectationsPath());
            this.blobKey = BlobKeys.forPersistedFile(blobStore, filePath);
            this.blobStore = blobStore;
            try {
                Files.createFile(filePath);
            } catch (FileAlreadyExistsException ignore) {
            } catch (Throwable throwable) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception creating persisted recorded expectations file " + filePath)
                        .setThrowable(throwable)
                );
            }
            mockServerEventLog.registerListener(this);
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(INFO)
                        .setMessageFormat("created recorded expectation file system persistence for{}")
                        .setArguments(configuration.persistedRecordedExpectationsPath())
                );
            }
        } else {
            this.mockServerLogger = null;
            this.mockServerEventLog = null;
            this.objectWriter = null;
            this.filePath = null;
            this.blobKey = null;
            this.blobStore = null;
        }
    }

    /**
     * Backwards-compatible constructor that creates a
     * {@link org.mockserver.state.FilesystemBlobStore} internally, preserving
     * the original direct-file-I/O behaviour for callers that do not supply
     * a BlobStore (e.g. existing tests).
     */
    public RecordedExpectationFileSystemPersistence(Configuration configuration, MockServerLogger mockServerLogger, MockServerEventLog mockServerEventLog) {
        this(configuration, mockServerLogger, mockServerEventLog, new org.mockserver.state.FilesystemBlobStore(mockServerLogger));
    }

    @Override
    public void updated(MockServerEventLog mockServerLog) {
        if (mockServerEventLog == null) {
            return;
        }
        // held from the read to the write, so a write from an older read cannot land after one from a newer read
        writeOrderLock.lock();
        try {
            CompletableFuture<List<Expectation>> future = new CompletableFuture<>();
            mockServerLog.retrieveRecordedExpectations(null, future::complete);
            List<Expectation> expectations = future.get(30, TimeUnit.SECONDS);
            writeToBlob(expectations);
        } catch (Exception e) {
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception while retrieving recorded expectations for persistence")
                        .setThrowable(e)
                );
            }
        } finally {
            writeOrderLock.unlock();
        }
    }

    private void writeToBlob(List<Expectation> expectations) {
        // The lock serialises serialize + write-to-blob as one atomic unit,
        // matching the original fileWriteLock semantics. This is necessary
        // because listener callbacks may be dispatched concurrently.
        writeOrderLock.lock();
        try {
            try {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(TRACE)
                            .setMessageFormat("persisting recorded expectations{}to{}")
                            .setArguments(expectations, configuration.persistedRecordedExpectationsPath())
                    );
                } else if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(DEBUG)
                            .setMessageFormat("persisting recorded expectations to{}")
                            .setArguments(configuration.persistedRecordedExpectationsPath())
                    );
                }
                byte[] data = serialize(expectations).getBytes(UTF_8);
                blobStore.put(blobKey, data, Collections.emptyMap());
            } catch (Throwable throwable) {
                if (mockServerLogger != null) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("exception while persisting recorded expectations to " + filePath.toString())
                            .setThrowable(throwable)
                    );
                }
            }
        } finally {
            writeOrderLock.unlock();
        }
    }

    public String serialize(List<Expectation> expectations) {
        return serialize(expectations.toArray(new Expectation[0]));
    }

    public String serialize(Expectation... expectations) {
        try {
            if (expectations != null && expectations.length > 0) {
                ExpectationDTO[] expectationDTOs = new ExpectationDTO[expectations.length];
                for (int i = 0; i < expectations.length; i++) {
                    expectationDTOs[i] = new ExpectationDTO(expectations[i]);
                }
                return objectWriter.writeValueAsString(expectationDTOs);
            } else {
                return "[]";
            }
        } catch (Exception e) {
            String description = SerializationFailure.describe(expectations, e);
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception while serializing recorded expectation to JSON:{}")
                        .setArguments(description)
                        .setThrowable(e)
                );
            }
            throw new RuntimeException("Exception while serializing recorded expectation to JSON (" + description + ")", e);
        }
    }

    /**
     * Writes the file once more, so a recording made in the last moments before the stop (which the event log's
     * coalesced notification would write up to 250 ms later, after this listener is gone) is not lost, then stops
     * listening. Returns once the file is written. Only the first call writes, as the event log stops after it.
     */
    public void stop() {
        if (mockServerEventLog != null && stopped.compareAndSet(false, true)) {
            updated(mockServerEventLog);
            mockServerEventLog.unregisterListener(this);
        }
    }
}

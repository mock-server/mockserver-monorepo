package org.mockserver.persistence;

import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.slf4j.event.Level.INFO;

public class FileWatcher {

    private static ScheduledExecutorService scheduler;

    public synchronized static ScheduledExecutorService getScheduler() {
        if (scheduler == null) {
            scheduler = new ScheduledThreadPoolExecutor(
                2,
                new Scheduler.SchedulerThreadFactory("FileWatcher"),
                new ThreadPoolExecutor.CallerRunsPolicy()
            );
            Runtime.getRuntime().addShutdownHook(new Thread(() -> scheduler.shutdown()));
        }
        return scheduler;
    }

    private volatile boolean running = true;
    private final ScheduledFuture<?> scheduledFuture;

    /**
     * @param pollPeriodMillis the interval, in milliseconds, between successive polls of the watched
     *                         file. Captured here when {@code scheduleAtFixedRate} is set up, so the
     *                         first poll lands one full period after construction. The poll period is
     *                         supplied per-watcher (via the {@code watchInitializationJsonPollPeriodMillis}
     *                         configuration property, see {@link org.mockserver.configuration.Configuration})
     *                         rather than held in shared mutable static state, so concurrent watchers /
     *                         tests can use different periods without racing each other. A non-positive
     *                         value is clamped up to {@code 1ms} to satisfy {@code scheduleAtFixedRate}.
     */
    public FileWatcher(Path filePath, Runnable updatedHandler, Consumer<Throwable> errorHandler, MockServerLogger mockServerLogger, long pollPeriodMillis) {
        final long pollPeriod = Math.max(1L, pollPeriodMillis);
        final Path path = filePath.getParent() != null ? filePath : Paths.get(new File(".").getAbsolutePath(), filePath.toString());
        final AtomicReference<Integer> fileHash = new AtomicReference<>(getFileHash(path));
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(INFO)
                .setMessageFormat("watching file:{}with file fingerprint:{}")
                .setArguments(path, fileHash)
        );
        scheduledFuture = getScheduler().scheduleAtFixedRate(() -> {
            // Skip the iteration entirely if this watcher has been stopped but
            // the already-scheduled task has not yet been cancelled.
            if (!running) {
                return;
            }
            try {
                Integer currentHash = getFileHash(path);
                // A null hash means the file could not be read (missing, being
                // rewritten, or otherwise unreadable). Do NOT treat that as a
                // content change: skip this poll iteration and keep the previous
                // hash so the next successful read decides whether to reload.
                // Conflating "unreadable" with "changed" (the old return-0
                // behaviour) caused spurious reloads of partial/empty content
                // mid-rewrite and could mask a real subsequent change.
                if (currentHash == null) {
                    return;
                }
                if (!currentHash.equals(fileHash.get())) {
                    updatedHandler.run();
                    // Re-read after the handler ran; only update the stored hash
                    // when the re-read succeeds, otherwise keep the value we just
                    // acted on so a transient read failure does not lose state.
                    Integer afterHash = getFileHash(path);
                    fileHash.set(afterHash != null ? afterHash : currentHash);
                }
            } catch (Throwable throwable) {
                errorHandler.accept(throwable);
            }
        }, pollPeriod, pollPeriod, TimeUnit.MILLISECONDS);
    }

    /**
     * Computes a content fingerprint of the watched file, or returns
     * {@code null} when the file cannot be read (missing, mid-rewrite, or
     * otherwise unreadable). Callers must treat {@code null} as "no reliable
     * reading this iteration" rather than as a content change.
     */
    private Integer getFileHash(Path path) {
        try {
            return Arrays.hashCode(Files.readAllBytes(path));
        } catch (IOException ioe) {
            return null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public FileWatcher setRunning(boolean running) {
        this.running = running;
        if (!running && this.scheduledFuture != null) {
            this.scheduledFuture.cancel(true);
        }
        return this;
    }
}

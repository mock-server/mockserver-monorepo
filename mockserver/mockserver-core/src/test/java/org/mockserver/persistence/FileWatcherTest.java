package org.mockserver.persistence;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.test.Retries;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link FileWatcher} focusing on the two correctness fixes:
 * <ol>
 *     <li>a failed file read (missing / mid-rewrite / unreadable) must NOT be
 *     treated as a content change — the poll iteration is skipped and the
 *     previous hash is preserved; and</li>
 *     <li>a stopped watcher (via {@link FileWatcher#setRunning(boolean)}) must
 *     stop invoking the updated handler.</li>
 * </ol>
 *
 * <p>Passes a short poll period (500ms) directly to each {@link FileWatcher} it
 * constructs. The poll period is now a per-watcher constructor argument rather than
 * shared mutable static state, so this test mutates no JVM-global state and cannot
 * race any other watcher test on it — the shared-static restore race that once
 * reddened master builds 6914/6918 and PR #2655 is structurally impossible now.</p>
 *
 * <p>The retry budgets below remain deliberately generous (~15s) as harmless
 * defence in depth against scheduler-thread contention under a loaded machine.</p>
 */
public class FileWatcherTest {

    /**
     * Short poll period (in milliseconds) passed to each watcher so content changes
     * are detected within the test's assertion budget.
     */
    private static final long POLL_PERIOD_MILLIS = 500;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @Test
    public void shouldInvokeUpdatedHandlerWhenFileContentChanges() throws Exception {
        // given
        File watchedFile = temporaryFolder.newFile("watched-" + System.nanoTime() + ".json");
        Files.write(watchedFile.toPath(), "initial".getBytes(StandardCharsets.UTF_8));
        AtomicInteger updateCount = new AtomicInteger(0);
        FileWatcher fileWatcher = new FileWatcher(watchedFile.toPath(), updateCount::incrementAndGet, throwable -> {
        }, mockServerLogger, POLL_PERIOD_MILLIS);
        try {
            // when - the content changes
            Files.write(watchedFile.toPath(), "changed".getBytes(StandardCharsets.UTF_8));

            // then - the updated handler fires
            Retries.tryWaitForSuccess(() -> assertThat(updateCount.get(), greaterThanOrEqualTo(1)), 150, 100, MILLISECONDS);
        } finally {
            fileWatcher.setRunning(false);
        }
    }

    @Test
    public void shouldNotInvokeUpdatedHandlerWhenFileBecomesUnreadable() throws Exception {
        // given - a file that exists and is being watched
        File watchedFile = temporaryFolder.newFile("disappearing-" + System.nanoTime() + ".json");
        Files.write(watchedFile.toPath(), "present".getBytes(StandardCharsets.UTF_8));
        AtomicInteger updateCount = new AtomicInteger(0);
        FileWatcher fileWatcher = new FileWatcher(watchedFile.toPath(), updateCount::incrementAndGet, throwable -> {
        }, mockServerLogger, POLL_PERIOD_MILLIS);
        try {
            // when - the file is deleted so reads fail (IOException -> null hash)
            Files.delete(watchedFile.toPath());

            // then - give several poll cycles a chance to (wrongly) fire; a deleted
            // file must NOT be treated as a content change. Before the fix, the IOException
            // returned hash 0 which differs from the original content hash and would have
            // triggered exactly one spurious update.
            Thread.sleep(500 * 4);
            assertThat("a deleted/unreadable file must not be treated as a content change",
                updateCount.get(), equalTo(0));
        } finally {
            fileWatcher.setRunning(false);
        }
    }

    @Test
    public void shouldReloadWhenFileReappearsAfterBeingUnreadable() throws Exception {
        // given - a file that does not yet exist (initial hash is null)
        File watchedFile = new File(temporaryFolder.getRoot(), "later-" + System.nanoTime() + ".json");
        assertFalse(watchedFile.exists());
        AtomicInteger updateCount = new AtomicInteger(0);
        FileWatcher fileWatcher = new FileWatcher(watchedFile.toPath(), updateCount::incrementAndGet, throwable -> {
        }, mockServerLogger, POLL_PERIOD_MILLIS);
        try {
            // when - the file appears with content
            Files.write(watchedFile.toPath(), "appeared".getBytes(StandardCharsets.UTF_8));

            // then - the transition from unreadable (null) to readable content fires an update
            Retries.tryWaitForSuccess(() -> assertThat(updateCount.get(), greaterThanOrEqualTo(1)), 150, 100, MILLISECONDS);
        } finally {
            fileWatcher.setRunning(false);
        }
    }

    @Test
    public void shouldStopInvokingHandlerAfterSetRunningFalse() throws Exception {
        // given
        File watchedFile = temporaryFolder.newFile("stoppable-" + System.nanoTime() + ".json");
        Files.write(watchedFile.toPath(), "v0".getBytes(StandardCharsets.UTF_8));
        AtomicInteger updateCount = new AtomicInteger(0);
        FileWatcher fileWatcher = new FileWatcher(watchedFile.toPath(), updateCount::incrementAndGet, throwable -> {
        }, mockServerLogger, POLL_PERIOD_MILLIS);

        // when - the watcher is stopped before any change
        fileWatcher.setRunning(false);
        assertFalse("setRunning(false) should clear the running flag", fileWatcher.isRunning());

        // and - the file content changes afterwards
        Files.write(watchedFile.toPath(), "v1".getBytes(StandardCharsets.UTF_8));

        // then - no update is ever delivered
        Thread.sleep(500 * 3);
        assertThat("a stopped watcher must not deliver updates", updateCount.get(), equalTo(0));
    }

    @Test
    public void runningFlagDefaultsToTrueAndReflectsSetRunning() throws Exception {
        File watchedFile = temporaryFolder.newFile("flag-" + System.nanoTime() + ".json");
        Files.write(watchedFile.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        FileWatcher fileWatcher = new FileWatcher(watchedFile.toPath(), () -> {
        }, throwable -> {
        }, mockServerLogger, POLL_PERIOD_MILLIS);
        try {
            assertTrue("a freshly-created watcher should be running", fileWatcher.isRunning());
            fileWatcher.setRunning(false);
            assertFalse(fileWatcher.isRunning());
        } finally {
            fileWatcher.setRunning(false);
        }
    }
}

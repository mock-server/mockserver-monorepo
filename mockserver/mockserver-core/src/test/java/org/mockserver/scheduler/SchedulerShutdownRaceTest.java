package org.mockserver.scheduler;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@link Scheduler#scheduleUnlessShutDown}: whatever the moment shutdown lands, the task runs or the caller answers,
 * exactly once, never both and never neither.
 */
public class SchedulerShutdownRaceTest {

    private static final long WAIT_SECONDS = 30;

    private final AtomicInteger answers = new AtomicInteger();
    private ScheduledThreadPoolExecutor executor;

    @After
    public void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * The caller answers when the helper returns null, as a request handler would.
     */
    private void submitAndAnswer(Runnable task) {
        if (Scheduler.scheduleUnlessShutDown(executor, task, 0, TimeUnit.MILLISECONDS) == null) {
            answers.incrementAndGet();
        }
    }

    private static ScheduledThreadPoolExecutor callerRunsExecutor(int threads) {
        return new ScheduledThreadPoolExecutor(threads, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Test
    public void answersOnceWhenShutDownBeforeSubmitting() throws Exception {
        executor = callerRunsExecutor(1);
        executor.shutdown();

        submitAndAnswer(answers::incrementAndGet);

        assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat(answers.get(), is(1));
    }

    @Test
    public void answersOnceWhenShutdownLandsWhileTheTaskIsAlreadyRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor = new ScheduledThreadPoolExecutor(1, new ThreadPoolExecutor.CallerRunsPolicy()) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                ScheduledFuture<?> scheduled = super.schedule(command, delay, unit);
                // the worker has started the task, then shutdown lands, before the caller checks
                awaitQuietly(started);
                shutdown();
                return scheduled;
            }
        };

        submitAndAnswer(() -> {
            started.countDown();
            awaitQuietly(release);
            answers.incrementAndGet();
        });
        release.countDown();

        assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat("the running task answers, the caller does not", answers.get(), is(1));
    }

    @Test
    public void answersOnceWhenShutdownLandsWhileTheTaskIsQueued() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean shutDownOnSchedule = new AtomicBoolean();
        executor = new ScheduledThreadPoolExecutor(1, new ThreadPoolExecutor.CallerRunsPolicy()) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                ScheduledFuture<?> scheduled = super.schedule(command, delay, unit);
                if (shutDownOnSchedule.get()) {
                    shutdown();
                }
                return scheduled;
            }
        };
        // occupy the only thread so the task stays queued
        executor.execute(() -> {
            busy.countDown();
            awaitQuietly(release);
        });
        assertThat(busy.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        shutDownOnSchedule.set(true);
        submitAndAnswer(answers::incrementAndGet);
        release.countDown();

        assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat("the caller answers and the queued task never runs", answers.get(), is(1));
    }

    @Test
    public void runsTheTaskOnceWhenNotShutDown() throws Exception {
        executor = callerRunsExecutor(1);
        CountDownLatch ran = new CountDownLatch(1);

        ScheduledFuture<?> scheduled = Scheduler.scheduleUnlessShutDown(executor, () -> {
            answers.incrementAndGet();
            ran.countDown();
        }, 0, TimeUnit.MILLISECONDS);

        assertThat(scheduled, notNullValue());
        assertThat(ran.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
        executor.shutdown();
        assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat(answers.get(), is(1));
    }

    @Test
    public void returnsNullWhenTheExecutorRejects() {
        executor = new ScheduledThreadPoolExecutor(1);
        executor.shutdown();

        assertThat(Scheduler.scheduleUnlessShutDown(executor, answers::incrementAndGet, 0, TimeUnit.MILLISECONDS), nullValue());
        assertThat(answers.get(), is(0));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

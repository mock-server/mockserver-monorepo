package org.mockserver.mock;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.StreamingBody;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockserver.configuration.Configuration.configuration;

public class OpenRecordedStreamsTest {

    @Test
    public void shouldRecordAndForgetAStreamOnceItEnds() {
        OpenRecordedStreams openRecordedStreams = new OpenRecordedStreams();
        List<String> recorded = new CopyOnWriteArrayList<>();
        StreamingBody completed = new StreamingBody(1024, true);
        StreamingBody failed = new StreamingBody(1024, true);
        StreamingBody open = new StreamingBody(1024, true);
        openRecordedStreams.track(completed, () -> recorded.add("completed"));
        openRecordedStreams.track(failed, () -> recorded.add("failed"));
        openRecordedStreams.track(open, () -> recorded.add("open"));

        completed.complete();
        failed.error(new StreamingBody.StreamAbortedException("upstream closed mid-stream"));

        assertThat(recorded, containsInAnyOrder("completed", "failed"));
        assertThat(openRecordedStreams.size(), is(1));
        assertThat(open.isCompleted(), is(false));
    }

    @Test
    public void shouldEndEveryOpenStreamOnStopAndReturnOnceEachIsRecorded() {
        EventLoop eventLoop = new DefaultEventLoop();
        try {
            OpenRecordedStreams openRecordedStreams = new OpenRecordedStreams();
            List<String> recorded = new CopyOnWriteArrayList<>();
            StreamingBody first = stream(eventLoop);
            StreamingBody second = stream(eventLoop);
            StreamingBody ended = stream(eventLoop);
            openRecordedStreams.track(first, () -> recorded.add("first"));
            openRecordedStreams.track(second, () -> recorded.add("second"));
            openRecordedStreams.track(ended, () -> recorded.add("ended"));
            ended.complete();

            int notRecorded = openRecordedStreams.completeAllOnStop(5_000);

            assertThat(notRecorded, is(0));
            assertThat("every stream was recorded before the stop went on", recorded, containsInAnyOrder("ended", "first", "second"));
            assertThat(first.isCompletedByStop(), is(true));
            assertThat(second.isCompletedByStop(), is(true));
            assertThat(ended.isCompletedByStop(), is(false));
            assertThat(openRecordedStreams.size(), is(0));
        } finally {
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldWaitForARecordingStillRunningWhenTheStreamEndedByItself() throws Exception {
        EventLoop eventLoop = new DefaultEventLoop();
        try {
            OpenRecordedStreams openRecordedStreams = new OpenRecordedStreams();
            List<String> recorded = new CopyOnWriteArrayList<>();
            CountDownLatch recordingStarted = new CountDownLatch(1);
            StreamingBody endingByItself = stream(eventLoop);
            openRecordedStreams.track(endingByItself, () -> {
                recordingStarted.countDown();
                sleepQuietly(200);
                recorded.add("ended by itself");
            });
            eventLoop.execute(endingByItself::complete);
            assertThat(recordingStarted.await(5, TimeUnit.SECONDS), is(true));

            int notRecorded = openRecordedStreams.completeAllOnStop(5_000);

            assertThat(notRecorded, is(0));
            assertThat("the stop waited for the recording under way", recorded, containsInAnyOrder("ended by itself"));
            assertThat(endingByItself.isCompletedByStop(), is(false));
        } finally {
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldCountAStreamNotRecordedInTime() {
        EventLoop eventLoop = new DefaultEventLoop();
        CountDownLatch release = new CountDownLatch(1);
        try {
            OpenRecordedStreams openRecordedStreams = new OpenRecordedStreams();
            StreamingBody slow = stream(eventLoop);
            openRecordedStreams.track(slow, () -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(openRecordedStreams.completeAllOnStop(100), is(1));
        } finally {
            release.countDown();
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldEndAStreamTrackedAfterTheStopBegan() {
        OpenRecordedStreams openRecordedStreams = new OpenRecordedStreams();
        openRecordedStreams.completeAllOnStop(5_000);
        List<String> recorded = new CopyOnWriteArrayList<>();
        StreamingBody late = new StreamingBody(1024, true);

        openRecordedStreams.track(late, () -> recorded.add("late"));

        assertThat(late.isCompletedByStop(), is(true));
        assertThat(recorded, containsInAnyOrder("late"));
        assertThat(openRecordedStreams.size(), is(0));
    }

    @Test
    public void shouldReturnAtOnceWithNoOpenStream() {
        assertThat(new OpenRecordedStreams().completeAllOnStop(5_000), is(0));
    }

    @Test
    public void shouldNotWaitAgainForAStreamAlreadyWaitedForByAnEarlierStopCall() {
        EventLoop eventLoop = new DefaultEventLoop();
        CountDownLatch release = new CountDownLatch(1);
        try {
            OpenRecordedStreams openRecordedStreams = new OpenRecordedStreams();
            StreamingBody slow = stream(eventLoop);
            openRecordedStreams.track(slow, () -> awaitQuietly(release));
            assertThat(openRecordedStreams.completeAllOnStop(100), is(1));

            long start = System.nanoTime();
            int notRecorded = openRecordedStreams.completeAllOnStop(5_000);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat("the stuck stream is reported only once", notRecorded, is(0));
            assertThat("the second call returned without waiting", elapsedMillis, lessThan(1_000L));
        } finally {
            release.countDown();
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldWaitForAStreamOpenedBetweenStopCallsWithinTheRemainingBudget() {
        EventLoop eventLoop = new DefaultEventLoop();
        try {
            OpenRecordedStreams openRecordedStreams = new OpenRecordedStreams();
            assertThat(openRecordedStreams.completeAllOnStop(5_000), is(0));
            List<String> recorded = new CopyOnWriteArrayList<>();
            StreamingBody late = stream(eventLoop);
            openRecordedStreams.track(late, () -> {
                sleepQuietly(200);
                recorded.add("late");
            });

            int notRecorded = openRecordedStreams.completeAllOnStop(5_000);

            assertThat(notRecorded, is(0));
            assertThat(late.isCompletedByStop(), is(true));
            assertThat("the second call waited for the stream opened since the first", recorded, containsInAnyOrder("late"));
        } finally {
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldNotCountTimeSpentBetweenStopCallsAgainstTheWait() {
        EventLoop eventLoop = new DefaultEventLoop();
        try {
            OpenRecordedStreams openRecordedStreams = new OpenRecordedStreams();
            assertThat(openRecordedStreams.completeAllOnStop(1_000), is(0));
            // longer than the whole budget, as a drain between two stop calls can be
            sleepQuietly(1_200);
            List<String> recorded = new CopyOnWriteArrayList<>();
            openRecordedStreams.track(stream(eventLoop), () -> {
                sleepQuietly(100);
                recorded.add("opened during the drain");
            });

            int notRecorded = openRecordedStreams.completeAllOnStop(1_000);

            assertThat(notRecorded, is(0));
            assertThat(recorded, containsInAnyOrder("opened during the drain"));
        } finally {
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldWarnOnceAboutAStreamNotRecordedAcrossEveryStopCall() {
        EventLoop eventLoop = new DefaultEventLoop();
        CountDownLatch release = new CountDownLatch(1);
        MockServerLogger logger = spy(new MockServerLogger(OpenRecordedStreamsTest.class));
        doReturn(true).when(logger).isEnabledForInstance(Level.WARN);
        // counted as logged: the logger recycles each entry once it has been handled
        AtomicInteger warnings = new AtomicInteger();
        doAnswer(invocation -> {
            if (isNotRecordedWarning(invocation.getArgument(0))) {
                warnings.incrementAndGet();
            }
            return invocation.callRealMethod();
        }).when(logger).logEvent(any(LogEntry.class));
        HttpState state = new HttpState(configuration(), logger, mock(Scheduler.class));
        boolean stopped = false;
        try {
            state.getOpenRecordedStreams().track(stream(eventLoop), () -> awaitQuietly(release));

            state.completeOpenRecordedStreams(100);
            long start = System.nanoTime();
            stopped = true;
            state.stop();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(warnings.get(), is(1));
            assertThat("the stop did not wait again for the stuck stream", elapsedMillis, lessThan(OpenRecordedStreams.STOP_WAIT_MILLIS));
        } finally {
            if (!stopped) {
                state.stop();
            }
            release.countDown();
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    private static boolean isNotRecordedWarning(LogEntry logEntry) {
        return logEntry != null
            && logEntry.getLogLevel() == Level.WARN
            && logEntry.getMessageFormat().contains("still open at stop were not recorded");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static StreamingBody stream(EventLoop eventLoop) {
        StreamingBody body = new StreamingBody(1024, true);
        body.setEventLoop(eventLoop);
        return body;
    }

    private static void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

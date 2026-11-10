package org.mockserver.mock;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import org.junit.Test;
import org.mockserver.model.StreamingBody;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

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

package org.mockserver.mock;

import org.mockserver.model.StreamingBody;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The streamed forwards being recorded that have not yet ended, kept only when {@code recordOpenStreamsOnStop} is on,
 * so a stop can end them as complete and record them ({@link #completeAllOnStop}) rather than lose them.
 */
public class OpenRecordedStreams {

    /** The longest a stop waits for the open streams to be recorded; each is one task on its upstream's event loop. */
    public static final long STOP_WAIT_MILLIS = 5_000;

    /** Each open stream, with a future completed once it has been recorded. */
    private final Map<StreamingBody, CompletableFuture<Void>> open = new ConcurrentHashMap<>();
    private volatile boolean stopping;
    /** Guarded by {@code this}: what is left of the one wait budget every stop call shares, set by the first. */
    private long remainingWaitNanos;
    /** Guarded by {@code this}: the streams already counted as not recorded, so each is reported once. */
    private final Set<CompletableFuture<Void>> reportedNotRecorded = new HashSet<>();

    /**
     * Register {@code recording} as the stream's completion listener, and keep the stream until it has run, so a stop
     * can end and record the stream while it is open. A stream tracked once a stop has begun is ended at once, so it
     * cannot hold the stop open and then be lost.
     */
    public void track(StreamingBody streamingBody, Runnable recording) {
        CompletableFuture<Void> recorded = new CompletableFuture<>();
        open.put(streamingBody, recorded);
        streamingBody.addCompletionListener(() -> {
            try {
                recording.run();
            } finally {
                open.remove(streamingBody);
                recorded.complete(null);
            }
        });
        if (stopping) {
            streamingBody.completeOnStop();
        }
    }

    /**
     * End every open stream as complete because MockServer is stopping, and wait until each one has been recorded.
     * Every later {@link #track} ends its stream at once.
     * <p>
     * A stop may call this more than once. The wait is one budget shared by every call: the first call sets it, and
     * a later call still ends any stream opened since, but waits only for what is left of it. Only time spent waiting
     * here counts, so a stuck stream holds the stop for at most {@code timeoutMillis} in total.
     *
     * @param timeoutMillis the longest to wait for all of them, across every call; only the first call's value is used
     * @return how many open streams were not recorded in time and were not already counted by an earlier call
     */
    public synchronized int completeAllOnStop(long timeoutMillis) {
        if (!stopping) {
            remainingWaitNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            stopping = true;
        }
        List<CompletableFuture<Void>> recorded = new ArrayList<>();
        for (Map.Entry<StreamingBody, CompletableFuture<Void>> entry : open.entrySet()) {
            entry.getKey().completeOnStop();
            recorded.add(entry.getValue());
        }
        if (recorded.isEmpty()) {
            return 0;
        }
        if (remainingWaitNanos > 0) {
            long waitStart = System.nanoTime();
            try {
                CompletableFuture.allOf(recorded.toArray(new CompletableFuture[0])).get(remainingWaitNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // counted below
            } finally {
                remainingWaitNanos -= System.nanoTime() - waitStart;
            }
        }
        int notRecorded = 0;
        for (CompletableFuture<Void> future : recorded) {
            if (!future.isDone() && reportedNotRecorded.add(future)) {
                notRecorded++;
            }
        }
        return notRecorded;
    }

    int size() {
        return open.size();
    }
}

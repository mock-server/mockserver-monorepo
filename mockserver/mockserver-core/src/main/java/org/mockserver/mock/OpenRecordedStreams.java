package org.mockserver.mock;

import org.mockserver.model.StreamingBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     *
     * @param timeoutMillis the longest to wait for all of them
     * @return how many of the streams open at the stop were not recorded in time
     */
    public int completeAllOnStop(long timeoutMillis) {
        stopping = true;
        List<CompletableFuture<Void>> recorded = new ArrayList<>();
        for (Map.Entry<StreamingBody, CompletableFuture<Void>> entry : open.entrySet()) {
            entry.getKey().completeOnStop();
            recorded.add(entry.getValue());
        }
        if (recorded.isEmpty()) {
            return 0;
        }
        try {
            CompletableFuture.allOf(recorded.toArray(new CompletableFuture[0])).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // counted below
        }
        int notRecorded = 0;
        for (CompletableFuture<Void> future : recorded) {
            if (!future.isDone()) {
                notRecorded++;
            }
        }
        return notRecorded;
    }

    int size() {
        return open.size();
    }
}

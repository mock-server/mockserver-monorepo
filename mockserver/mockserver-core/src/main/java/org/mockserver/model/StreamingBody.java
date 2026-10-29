package org.mockserver.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A chunk sink for streaming response bodies. When MockServer proxies a streaming response
 * (Server-Sent Events or chunked without Content-Length), the response head is delivered
 * immediately while chunks flow through this sink. The sink captures bytes into a bounded
 * buffer (up to {@code maxCaptureBytes}) for the event log / dashboard while forwarding
 * every chunk to the subscribed consumer.
 * <p>
 * Thread-safety: {@link #addCompletionListener} may be called from a scheduler thread
 * (e.g. {@code HttpActionHandler.writeStreamingForwardActionResponse}) while
 * {@link #complete()} and {@link #error(Throwable)} are called from the Netty event loop.
 * All mutation of the {@code completed} flag and {@code completionListeners} list is
 * guarded by {@code synchronized(lock)} to ensure a listener is always fired exactly once —
 * immediately if already completed, otherwise when completion/error occurs.
 * <p>
 * The chunk path ({@link #subscribe}, {@link #addChunk}, {@link #complete}, {@link #error})
 * is serialised on the upstream channel's event loop to avoid races between chunk arrival
 * and subscription. Any chunks that arrive before {@code subscribe()} are buffered as
 * {@code byte[]} arrays in a pending list and drained in order when the subscriber connects.
 * <p>
 * Every chunk counts towards {@code maxUnwrittenBytes} from {@link #addChunk} until its subscriber reports it
 * written with {@link #chunkWritten}, including chunks still pending before {@code subscribe()}. Read pacing
 * cannot bound this, because a decoder expands one upstream read synchronously (one read of {@code zstd} can
 * decode to about 2 GiB), so a chunk that takes the total past the bound fails the stream with
 * {@link UnwrittenBytesLimitExceededException} instead.
 */
public class StreamingBody {

    private final Object lock = new Object();
    private final int maxCaptureBytes;
    private final ByteArrayOutputStream captureBuffer;
    private volatile boolean truncated;
    private boolean completed;
    private Throwable error;

    private volatile Consumer<ByteBuf> onChunk;
    private volatile Runnable onComplete;
    private volatile Consumer<Throwable> onError;
    private List<Runnable> completionListeners;
    private volatile Runnable requestMoreCallback;
    private volatile Runnable upstreamCloser;
    private volatile boolean closedByClient;
    private volatile boolean completedAfterClientClose;
    private volatile Consumer<ByteBuf> chunkObserver;

    /** The upstream channel's event loop, used to serialise subscribe/addChunk/complete/error. */
    private volatile EventLoop eventLoop;

    /** Chunks that arrived before subscribe() was called. Each entry is a copy of the chunk bytes. */
    private List<byte[]> pendingChunks;

    /**
     * Per-chunk arrival timestamps in nanoseconds (monotonic clock). The first entry
     * records the baseline; subsequent entries allow computing inter-chunk delays.
     * Populated only when {@code captureChunkTimestamps} is true.
     */
    private List<Long> chunkTimestampsNanos;

    /** Whether to record per-chunk monotonic timestamps on {@link #addChunk}. */
    private final boolean captureChunkTimestamps;

    /** The most chunk bytes added but not yet reported written; zero or less for no bound. */
    private final long maxUnwrittenBytes;
    /** With a bound, {@link #chunkWritten} requests the next upstream read only at or below this many unwritten bytes. */
    private final long requestMoreAtOrBelowBytes;
    private final AtomicLong unwrittenBytes = new AtomicLong();

    /** The most unwritten bytes at which {@link #chunkWritten} requests another read, when the bound allows it. */
    static final long REQUEST_MORE_AT_OR_BELOW_BYTES = 64 * 1024;

    public StreamingBody(int maxCaptureBytes) {
        this(maxCaptureBytes, false);
    }

    /**
     * @param maxCaptureBytes       maximum bytes to capture for the event log
     * @param captureChunkTimestamps when true, records a {@code System.nanoTime()} per chunk
     *                              for later per-chunk replay timing
     */
    public StreamingBody(int maxCaptureBytes, boolean captureChunkTimestamps) {
        this(maxCaptureBytes, captureChunkTimestamps, 0);
    }

    /**
     * @param maxCaptureBytes        maximum bytes to capture for the event log
     * @param captureChunkTimestamps when true, records a {@code System.nanoTime()} per chunk
     *                               for later per-chunk replay timing
     * @param maxUnwrittenBytes      the most chunk bytes added but not yet reported written by
     *                               {@link #chunkWritten}, past which the stream fails; zero or less for no bound
     */
    public StreamingBody(int maxCaptureBytes, boolean captureChunkTimestamps, long maxUnwrittenBytes) {
        this.maxUnwrittenBytes = maxUnwrittenBytes;
        this.requestMoreAtOrBelowBytes = Math.min(REQUEST_MORE_AT_OR_BELOW_BYTES, maxUnwrittenBytes / 4);
        this.maxCaptureBytes = Math.max(0, maxCaptureBytes);
        this.captureBuffer = new ByteArrayOutputStream(Math.min(this.maxCaptureBytes, 8192));
        this.pendingChunks = new ArrayList<>();
        this.captureChunkTimestamps = captureChunkTimestamps;
        this.chunkTimestampsNanos = captureChunkTimestamps ? new ArrayList<>() : null;
    }

    /**
     * Set the upstream channel's event loop. Must be called before any chunks arrive
     * (typically by the handler that creates the body). All chunk-path operations
     * ({@link #subscribe}, {@link #addChunk}, {@link #complete}, {@link #error}) are
     * serialised on this event loop.
     *
     * @param eventLoop the upstream channel's event loop
     */
    public void setEventLoop(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    /**
     * Observe every chunk as it arrives from the upstream, including any that arrived before this call and any past
     * the capture limit. Call it before {@link #subscribe}. The observer runs on the event loop, must not release or
     * keep the buffer, and is dropped if it throws. Read its result through {@link #afterChunksObserved}.
     */
    public void observeChunks(Consumer<ByteBuf> observer) {
        if (eventLoop != null && !eventLoop.inEventLoop()) {
            eventLoop.execute(() -> observeChunksOnEventLoop(observer));
        } else {
            observeChunksOnEventLoop(observer);
        }
    }

    private void observeChunksOnEventLoop(Consumer<ByteBuf> observer) {
        chunkObserver = observer;
        if (pendingChunks != null) {
            for (byte[] chunkBytes : pendingChunks) {
                ByteBuf buffered = Unpooled.wrappedBuffer(chunkBytes);
                try {
                    notifyChunkObserver(buffered);
                } finally {
                    buffered.release();
                }
            }
        }
    }

    private void notifyChunkObserver(ByteBuf chunk) {
        Consumer<ByteBuf> observer = chunkObserver;
        if (observer != null) {
            try {
                observer.accept(chunk);
            } catch (Exception e) {
                // an observer must never affect the relayed stream
                chunkObserver = null;
            }
        }
    }

    /**
     * Run a task on the event loop, behind an observer still waiting to be attached there. A completion listener can
     * run on another thread before {@link #observeChunks} has taken effect, so it reads the observer's result here.
     */
    public void afterChunksObserved(Runnable task) {
        if (eventLoop != null && !eventLoop.inEventLoop()) {
            try {
                eventLoop.execute(task);
                return;
            } catch (RejectedExecutionException e) {
                // the event loop has shut down, so nothing more will be observed
            }
        }
        task.run();
    }

    /**
     * Subscribe to receive streaming chunks. If an event loop has been set via
     * {@link #setEventLoop(EventLoop)} and the caller is not on that event loop, the
     * subscribe body is marshalled onto the event loop to serialise with addChunk/complete/error.
     * Any chunks that arrived before subscription are drained in order and then the first
     * upstream read is triggered via {@link #requestMore()}, unless with a bound the drained chunks
     * leave more unwritten than the watermark, when {@link #chunkWritten} triggers it instead.
     *
     * @param onChunk    called for each {@link ByteBuf} chunk; the consumer must NOT release the buffer, and must
     *                   call {@link #chunkWritten} with its size once it is written or discarded
     * @param onComplete called when the last chunk has been received
     * @param onError    called if the stream is interrupted (channel close, timeout, etc.)
     */
    public void subscribe(Consumer<ByteBuf> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
        if (eventLoop != null && !eventLoop.inEventLoop()) {
            eventLoop.execute(() -> subscribeOnEventLoop(onChunk, onComplete, onError));
        } else {
            subscribeOnEventLoop(onChunk, onComplete, onError);
        }
    }

    /**
     * Runs on the upstream event loop (or inline when no event loop is set). Sets subscriber
     * callbacks, drains any pending pre-subscribe chunks, replays terminal signals, and
     * triggers the first upstream read if the backlog allows it.
     */
    private void subscribeOnEventLoop(Consumer<ByteBuf> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
        this.onChunk = onChunk;
        this.onComplete = onComplete;
        this.onError = onError;

        // Drain any chunks that arrived before subscribe was called
        if (pendingChunks != null && !pendingChunks.isEmpty()) {
            List<byte[]> pending = pendingChunks;
            pendingChunks = null;
            for (byte[] chunkBytes : pending) {
                ByteBuf buffered = Unpooled.wrappedBuffer(chunkBytes);
                try {
                    onChunk.accept(buffered);
                } finally {
                    buffered.release();
                }
            }
        } else {
            pendingChunks = null;
        }

        // Replay terminal signal if the stream completed/errored before subscribe
        if (completed) {
            if (error != null && onError != null) {
                onError.accept(error);
            } else if (error == null && onComplete != null) {
                onComplete.run();
            }
            return;
        }

        // Chunks drained above the watermark are still unwritten: chunkWritten requests the read once they drain
        if (maxUnwrittenBytes <= 0 || unwrittenBytes.get() <= requestMoreAtOrBelowBytes) {
            requestMore();
        }
    }

    /**
     * Feed a chunk into the sink. The chunk bytes are appended to the bounded capture
     * buffer and forwarded to the subscriber. If no subscriber has connected yet, the
     * full chunk bytes are copied into a pending list so they can be drained when
     * {@link #subscribe} runs.
     *
     * @param chunk the chunk content (caller retains ownership of the buffer)
     * @return false when the chunk was not taken: the stream has ended, or this chunk took it past
     * {@code maxUnwrittenBytes} and failed it, so the caller should stop reading the upstream
     */
    public boolean addChunk(ByteBuf chunk) {
        if (completed) {
            return false;
        }
        int readable = chunk.readableBytes();
        if (maxUnwrittenBytes > 0 && unwrittenBytes.addAndGet(readable) > maxUnwrittenBytes) {
            // nothing pending is delivered once the stream has failed this way
            pendingChunks = null;
            error(new UnwrittenBytesLimitExceededException(maxUnwrittenBytes));
            return false;
        }
        // record monotonic timestamp for per-chunk replay timing (stop once truncated —
        // a truncated recording falls back to a static/fixed response so per-chunk timing
        // is moot, and continuing to record timestamps would grow the list without bound
        // on long-lived streams)
        if (chunkTimestampsNanos != null && !truncated) {
            chunkTimestampsNanos.add(System.nanoTime());
        }
        // capture into bounded buffer
        if (!truncated && readable > 0) {
            int remaining = maxCaptureBytes - captureBuffer.size();
            if (remaining > 0) {
                int toCapture = Math.min(readable, remaining);
                byte[] bytes = new byte[toCapture];
                chunk.getBytes(chunk.readerIndex(), bytes);
                captureBuffer.write(bytes, 0, toCapture);
                if (captureBuffer.size() >= maxCaptureBytes) {
                    truncated = true;
                    // discard timestamps — truncated recordings use fixed-delay fallback
                    chunkTimestampsNanos = null;
                }
            } else {
                truncated = true;
                // discard timestamps — truncated recordings use fixed-delay fallback
                chunkTimestampsNanos = null;
            }
        }
        if (chunkObserver != null) {
            notifyChunkObserver(chunk.slice());
        }
        // forward to subscriber or buffer for later
        if (onChunk != null) {
            onChunk.accept(chunk);
        } else if (pendingChunks != null) {
            // No subscriber yet — copy the full chunk bytes into the pending list
            byte[] copy = new byte[readable];
            chunk.getBytes(chunk.readerIndex(), copy);
            pendingChunks.add(copy);
        }
        return true;
    }

    /**
     * Report that a chunk passed to the subscriber has been written to the client, or discarded, so its bytes no
     * longer count towards {@code maxUnwrittenBytes}. With a bound, the next upstream read is requested only once the
     * unwritten bytes have drained to a low watermark (min(64 KiB, bound / 4)), so how many chunks one read held
     * cannot grow the backlog; the write that drains it there always requests one. Without a bound every call
     * requests one. Safe to call from any thread.
     *
     * @param bytes the chunk's readable bytes when it was passed to the subscriber
     */
    public void chunkWritten(int bytes) {
        long unwritten = unwrittenBytes.addAndGet(-bytes);
        if (maxUnwrittenBytes <= 0 || unwritten <= requestMoreAtOrBelowBytes) {
            requestMore();
        }
    }

    /**
     * Signal that the stream is complete (last chunk received).
     */
    public void complete() {
        complete(false);
    }

    /**
     * Signal that the stream is complete because the client closed it, so what was captured, with its chunk timing, is
     * all there will be. Does nothing if the stream has already completed or errored, so an upstream that ends at the
     * same moment decides the outcome and no listener fires twice.
     */
    public void completeAfterClientClose() {
        complete(true);
    }

    private void complete(boolean afterClientClose) {
        List<Runnable> listenersToFire;
        synchronized (lock) {
            if (completed) {
                return;
            }
            completed = true;
            completedAfterClientClose = afterClientClose;
            listenersToFire = completionListeners;
            completionListeners = null;
        }
        if (onComplete != null) {
            onComplete.run();
        }
        fireListeners(listenersToFire);
    }

    /**
     * Signal that the stream was interrupted by an error.
     *
     * @param cause the cause of the interruption
     */
    public void error(Throwable cause) {
        List<Runnable> listenersToFire;
        synchronized (lock) {
            if (completed) {
                return;
            }
            completed = true;
            error = cause;
            truncated = true;
            chunkTimestampsNanos = null;
            listenersToFire = completionListeners;
            completionListeners = null;
        }
        if (onError != null) {
            onError.accept(cause);
        }
        fireListeners(listenersToFire);
    }

    /**
     * Add a listener that is called when the stream completes (either successfully or
     * with an error). If the stream has already completed, the listener is called
     * immediately. This method is safe to call from any thread.
     *
     * @param listener the completion listener
     */
    public void addCompletionListener(Runnable listener) {
        boolean runNow;
        synchronized (lock) {
            if (completed) {
                runNow = true;
            } else {
                runNow = false;
                if (completionListeners == null) {
                    completionListeners = new ArrayList<>();
                }
                completionListeners.add(listener);
            }
        }
        if (runNow) {
            listener.run();
        }
    }

    private void fireListeners(List<Runnable> listeners) {
        if (listeners != null) {
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (Exception ignored) {
                    // don't let a listener failure propagate
                }
            }
        }
    }

    /**
     * Set a callback that is invoked to request the next chunk from the upstream channel.
     * Used for backpressure: {@link #chunkWritten} invokes it, through {@link #requestMore()},
     * once the unwritten backlog has drained, which triggers an upstream {@code ctx.read()}.
     *
     * @param callback the callback to request the next upstream chunk
     */
    public void setRequestMoreCallback(Runnable callback) {
        this.requestMoreCallback = callback;
    }

    /**
     * Request the next chunk from the upstream channel, whatever the backlog. Writers report
     * written chunks with {@link #chunkWritten} instead, which calls this when the backlog allows.
     */
    public void requestMore() {
        if (requestMoreCallback != null) {
            requestMoreCallback.run();
        }
    }

    /**
     * @return true if the capture buffer was capped and does not contain the full body
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * @return true if the stream has completed (successfully or with an error)
     */
    public boolean isCompleted() {
        synchronized (lock) {
            return completed;
        }
    }

    /**
     * @return the error that interrupted the stream, or null if it completed normally
     */
    public Throwable getError() {
        synchronized (lock) {
            return error;
        }
    }

    /**
     * @return the captured bytes (may be fewer than the full body if truncated)
     */
    public byte[] capturedBytes() {
        return captureBuffer.toByteArray();
    }

    /**
     * Compute inter-chunk delays in milliseconds from the captured monotonic timestamps.
     * The first element is always 0 (no delay before the first chunk). Subsequent elements
     * represent the wall-clock gap between consecutive chunk arrivals.
     *
     * @return a list of inter-chunk delays in milliseconds, or {@code null} if timestamps
     *         were not captured (i.e. {@code captureChunkTimestamps} was false)
     */
    public List<Long> interChunkDelaysMillis() {
        if (chunkTimestampsNanos == null || chunkTimestampsNanos.isEmpty()) {
            return null;
        }
        List<Long> delays = new ArrayList<>(chunkTimestampsNanos.size());
        delays.add(0L); // first chunk has no preceding delay
        for (int i = 1; i < chunkTimestampsNanos.size(); i++) {
            long deltaNanos = chunkTimestampsNanos.get(i) - chunkTimestampsNanos.get(i - 1);
            delays.add(Math.max(0, deltaNanos / 1_000_000L)); // nanos to millis, floor at 0
        }
        return delays;
    }

    /**
     * @return true while more bytes wait for the client than the watermark at which {@link #chunkWritten} requests
     * another read, so the upstream is not being read until the client takes them; always false without a bound
     */
    public boolean isAwaitingClient() {
        // with no subscriber nothing will ever write the waiting bytes, so nothing is awaited
        return onChunk != null && maxUnwrittenBytes > 0 && unwrittenBytes.get() > requestMoreAtOrBelowBytes;
    }

    /**
     * Set how to close the upstream connection (or stream) this body is read from.
     */
    public void setUpstreamCloser(Runnable upstreamCloser) {
        this.upstreamCloser = upstreamCloser;
    }

    /**
     * Close the upstream this body is read from, for a subscriber that has ended the client's response itself and
     * will take no more of the stream. Safe to call from any thread.
     */
    public void closeUpstream() {
        Runnable closer = upstreamCloser;
        if (closer != null) {
            closer.run();
        }
    }

    /**
     * Close the upstream this body is read from because the client has gone, and note that it was the client that
     * ended the stream. The upstream's close then tells {@link #completeAfterClientClose()} apart from an upstream
     * that failed by itself. Safe to call from any thread.
     */
    public void closeUpstreamForClientClose() {
        closedByClient = true;
        closeUpstream();
    }

    /**
     * @return whether the client closed the stream before it ended (see {@link #closeUpstreamForClientClose()})
     */
    public boolean isClosedByClient() {
        return closedByClient;
    }

    /**
     * @return whether the stream was completed because the client closed it (see {@link #completeAfterClientClose()})
     */
    public boolean isCompletedAfterClientClose() {
        return completedAfterClientClose;
    }

    /**
     * The stream was cut short by MockServer, so a writer must end the client's response as incomplete (close the
     * connection or reset the stream) rather than with a normal terminating chunk, which would make it look whole.
     */
    public static class StreamAbortedException extends RuntimeException {

        public StreamAbortedException(String message) {
            super(message);
        }

        public StreamAbortedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The stream failed because more chunk bytes were added than {@code maxUnwrittenBytes} while not yet written.
     */
    public static final class UnwrittenBytesLimitExceededException extends StreamAbortedException {

        public UnwrittenBytesLimitExceededException(long maxUnwrittenBytes) {
            super("streamed response passed " + maxUnwrittenBytes + " bytes not yet written to the client");
        }
    }

    /**
     * The upstream sent nothing for the stream idle timeout while MockServer was reading it, or while nothing had
     * subscribed to the stream (a response replaced or dropped before it was written).
     */
    public static final class IdleTimeoutException extends StreamAbortedException {

        public IdleTimeoutException(long idleTimeoutSeconds) {
            super("streamed response received nothing from the upstream for " + idleTimeoutSeconds + " seconds");
        }
    }
}

package org.mockserver.netty.connection;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2RemoteFlowController;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Resets an HTTP/2 stream whose response has data waiting in the connection's flow controller that the client has
 * taken none of for {@code responseWriteStallTimeoutMillis}. A client can stop granting one stream flow-control window
 * while it keeps reading the connection, so a stalled stream is invisible at the connection level;
 * {@link WriteStallTimeoutHandler} covers a connection whose socket stalls.
 * <p>
 * A stream progresses only when the flow controller writes some of its data, which {@link WrittenBytesFlowController}
 * counts as it leaves. The window a client returns for a stream never counts as progress: it only tells which of the
 * streams already timed out is reset first. What else spares a stream whose own window is open, and which stream is
 * reset when one holds the connection window the others wait for, is in "Response Write-Stall Timeout" in
 * docs/code/netty-pipeline.md.
 * <p>
 * Sits anywhere after the connection's {@link Http2ConnectionHandler}, whose remote flow controller it wraps. A timer
 * runs only while the connection has active streams.
 */
public final class Http2StreamWriteStallHandler extends ChannelDuplexHandler implements Runnable {

    private final long timeoutMillis;
    private final long timeoutNanos;
    private final long checkIntervalMillis;
    private final MockServerLogger mockServerLogger;
    private final List<Http2Stream> unmovedStreams = new ArrayList<>();
    private final List<Integer> stalledStreamIds = new ArrayList<>();
    private ChannelHandlerContext ctx;
    private ChannelHandlerContext codecCtx;
    private Http2ConnectionHandler codec;
    private Http2Connection connection;
    private Http2Connection.PropertyKey progressKey;
    private Http2Connection.PropertyKey sentKey;
    private ScheduledFuture<?> check;
    private long now;
    private long connectionWritten;
    private long lastConnectionWritten;
    private long connectionUnreturned;
    private boolean anyProgress;
    private boolean holderWaiting;

    public Http2StreamWriteStallHandler(long timeoutMillis, MockServerLogger mockServerLogger) {
        this.timeoutMillis = timeoutMillis;
        this.timeoutNanos = MILLISECONDS.toNanos(timeoutMillis);
        this.checkIntervalMillis = WriteStallTimeoutHandler.checkIntervalMillis(timeoutMillis);
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.codecCtx = ctx.pipeline().context(Http2ConnectionHandler.class);
        if (codecCtx == null) {
            throw new IllegalStateException(Http2StreamWriteStallHandler.class.getSimpleName() + " must be added after an " + Http2ConnectionHandler.class.getSimpleName());
        }
        this.codec = (Http2ConnectionHandler) codecCtx.handler();
        this.connection = codec.connection();
        this.progressKey = connection.newKey();
        this.sentKey = connection.newKey();
        connection.remote().flowController(new WrittenBytesFlowController(connection.remote().flowController(), new WrittenBytesFlowController.Observer() {
            @Override
            public void written(Http2Stream stream, int bytes) {
                Http2StreamWriteStallHandler.this.written(stream, bytes);
            }

            @Override
            public void windowIncremented(Http2Stream stream, int bytes) {
                windowReturned(stream, bytes);
            }
        }));
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (check != null) {
            check.cancel(false);
            check = null;
        }
    }

    private void written(Http2Stream stream, int bytes) {
        StreamData sent = stream.getProperty(sentKey);
        if (sent == null) {
            stream.setProperty(sentKey, sent = new StreamData());
        }
        sent.written += bytes;
        sent.unreturned += bytes;
        connectionWritten += bytes;
        connectionUnreturned += bytes;
    }

    // window granted beyond the data sent returns nothing, so a grant cannot hide what a stream holds
    private void windowReturned(Http2Stream stream, int bytes) {
        if (stream.id() == Http2CodecUtil.CONNECTION_STREAM_ID) {
            connectionUnreturned -= Math.min(bytes, connectionUnreturned);
            return;
        }
        StreamData sent = stream.getProperty(sentKey);
        long returned = sent != null ? Math.min(bytes, sent.unreturned) : 0;
        if (returned > 0) {
            sent.unreturned -= returned;
            if (sent.smallestReturn == 0 || returned < sent.smallestReturn) {
                sent.smallestReturn = returned;
            }
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        ctx.flush();
        if (check == null && connection.numActiveStreams() > 0) {
            check = ctx.executor().schedule(this, checkIntervalMillis, MILLISECONDS);
        }
    }

    @Override
    public void run() {
        check = null;
        if (!ctx.channel().isActive() || connection.numActiveStreams() == 0) {
            return;
        }
        now = System.nanoTime();
        Http2RemoteFlowController flowController = connection.remote().flowController();
        // data written for any stream, including one that has since closed
        anyProgress = connectionWritten != lastConnectionWritten;
        lastConnectionWritten = connectionWritten;
        holderWaiting = false;
        try {
            connection.forEachActiveStream(stream -> {
                checkStream(stream, flowController);
                return true;
            });
        } catch (Http2Exception unexpected) {
            // the visitor throws nothing; anything else leaves the streams as they are until the next check
        }
        // the connection's own channel: its socket is what the flow controller waits on to write an open-windowed stream;
        // a socket stall is left to WriteStallTimeoutHandler only where one is watching this connection
        boolean socketStallWatched = !ctx.channel().isWritable() && ctx.pipeline().get(WriteStallTimeoutHandler.class) != null;
        selectStalledStreams(flowController.windowSize(connection.connectionStream()), socketStallWatched);
        unmovedStreams.clear();
        for (int streamId : stalledStreamIds) {
            reset(streamId);
        }
        if (!stalledStreamIds.isEmpty()) {
            stalledStreamIds.clear();
            codecCtx.flush();
        }
        check = ctx.executor().schedule(this, checkIntervalMillis, MILLISECONDS);
    }

    /**
     * Picks the unmoved streams to reset. While the connection window is closed, a reset that should release it (a
     * closed-window stream, or those holding the most of it) gives each of the others one fresh period, and not
     * another until it is refreshed for any other reason. A stream holding less than its client has returned for it
     * at once is passed over when another, with no such history, holds more than half of what the connection has
     * out. See "A stalled stream holding the connection window" in docs/code/netty-pipeline.md.
     */
    private void selectStalledStreams(int connectionWindow, boolean socketStallWatched) {
        boolean openWindowsRefreshed = anyProgress || socketStallWatched;
        // a stream waiting for a closed connection window is not reset before the streams that may hold it time out
        boolean openWindowsTimed = !openWindowsRefreshed && !(connectionWindow <= 0 && holderWaiting);
        long mostHeld = Long.MIN_VALUE;
        long mostHeldUnexplained = Long.MIN_VALUE;
        for (Http2Stream stream : unmovedStreams) {
            StreamWriteProgress progress = stream.getProperty(progressKey);
            progress.resetting = false;
            mostHeld = Math.max(mostHeld, progress.held);
            if (!progress.explained) {
                mostHeldUnexplained = Math.max(mostHeldUnexplained, progress.held);
            }
        }
        // one stream alone, not several together: a stream being consumed can have up to half a window unreturned
        // before its first return, so only more than half of what the connection has out marks a stream as stalled
        boolean passOverExplained = mostHeldUnexplained > connectionUnreturned / 2;
        if (passOverExplained) {
            mostHeld = mostHeldUnexplained;
        }
        boolean windowReleased = false;
        for (Http2Stream stream : unmovedStreams) {
            StreamWriteProgress progress = stream.getProperty(progressKey);
            if (timedOut(progress, openWindowsTimed)) {
                // streams holding the same cannot be told apart, so all are reset and the stalled one is among them
                if (progress.window <= 0 || (progress.held == mostHeld && !(passOverExplained && progress.explained))) {
                    progress.resetting = true;
                    stalledStreamIds.add(stream.id());
                    // a stream sent nothing holds no connection window, so resetting one never extends another's time
                    windowReleased |= connectionWindow <= 0 && progress.held > 0;
                }
            }
        }
        for (Http2Stream stream : unmovedStreams) {
            StreamWriteProgress progress = stream.getProperty(progressKey);
            if (progress.window <= 0 || progress.resetting) {
                continue;
            }
            if (openWindowsRefreshed) {
                progress.lastProgressNanos = now;
            } else if (windowReleased && progress.lastProgressNanos != progress.releaseGrantedNanos) {
                progress.lastProgressNanos = progress.releaseGrantedNanos = now;
            } else if (timedOut(progress, openWindowsTimed)) {
                stalledStreamIds.add(stream.id());
            }
        }
    }

    private boolean timedOut(StreamWriteProgress progress, boolean openWindowsTimed) {
        return now - progress.lastProgressNanos >= timeoutNanos && (progress.window <= 0 || openWindowsTimed);
    }

    private void checkStream(Http2Stream stream, Http2RemoteFlowController flowController) {
        StreamWriteProgress progress = stream.getProperty(progressKey);
        // a stream already reset stays active until its RST_STREAM is written, which a stalled socket delays
        if (stream.isResetSent() || !flowController.hasFlowControlled(stream)) {
            if (progress != null) {
                stream.removeProperty(progressKey);
            }
            return;
        }
        int window = flowController.windowSize(stream);
        StreamData sent = stream.getProperty(sentKey);
        long written = sent != null ? sent.written : 0;
        if (progress == null) {
            stream.setProperty(progressKey, new StreamWriteProgress(window, written, now));
            holderWaiting |= written > 0;
            return;
        }
        boolean moved = written != progress.written;
        progress.window = window;
        progress.written = written;
        progress.held = sent != null ? sent.unreturned : 0;
        progress.explained = sent != null && sent.unreturned < sent.smallestReturn;
        if (moved) {
            progress.lastProgressNanos = now;
        } else {
            unmovedStreams.add(stream);
            // only a stream that has been sent data can hold connection window, and data cannot be faked by a client
            holderWaiting |= written > 0 && now - progress.lastProgressNanos < timeoutNanos;
        }
    }

    private void reset(int streamId) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("resetting HTTP/2 stream:{}from:{}because its client took none of the response waiting for it for:{}ms (responseWriteStallTimeoutMillis), so the response ends incomplete")
                    .setArguments(streamId, ctx.channel().remoteAddress(), timeoutMillis)
            );
        }
        Metrics.incrementResponseWriteStalls(Metrics.ResponseWriteStall.HTTP2_STREAM);
        ServerClosedChannels.markHttp2StreamReset(ctx.channel(), streamId);
        codec.resetStream(codecCtx, streamId, Http2Error.CANCEL.code(), codecCtx.newPromise());
    }

    // kept from the first data written for a stream
    private static final class StreamData {
        private long written;
        // written, less the window its client has since returned for it: the most of it the client can be holding
        private long unreturned;
        // the least its client has returned for it at once, 0 until it returns any
        private long smallestReturn;
    }

    private static final class StreamWriteProgress {
        private int window;
        private long written;
        private long held;
        // holds less than its client has left unreturned on it before returning, as a stream being consumed does
        private boolean explained;
        private long lastProgressNanos;
        private boolean resetting;
        // equal to lastProgressNanos while the stream's current period is the fresh one given for a reset
        private long releaseGrantedNanos;

        private StreamWriteProgress(int window, long written, long lastProgressNanos) {
            this.window = window;
            this.written = written;
            this.lastProgressNanos = lastProgressNanos;
            this.releaseGrantedNanos = lastProgressNanos - 1;
        }
    }
}

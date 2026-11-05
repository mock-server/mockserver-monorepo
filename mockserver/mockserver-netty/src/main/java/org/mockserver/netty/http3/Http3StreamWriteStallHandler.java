package org.mockserver.netty.http3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicTransportParameters;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.PromiseCombiner;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.slf4j.event.Level;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;

/**
 * Resets an HTTP/3 request stream ({@code RESET_STREAM H3_INTERNAL_ERROR}) when writes have been waiting on it for
 * {@code responseWriteStallTimeoutMillis} and QUIC has taken none of them, so a client that stops reading a response
 * cannot hold it, or a streamed response's upstream, indefinitely.
 * <p>
 * QUIC completes a write only once it has taken all of it, and reports nothing of a part. So every write, whichever
 * handler made it, is passed on in parts of at most {@link #MAX_WRITE_BYTES}, and a slow reader of one large frame
 * shows progress; the bytes on the wire are unchanged. QUIC can complete an earlier write while it is given a later
 * one, so a write made from a listener of that completion is held until the write being passed on has been, whole.
 * Installed first in each request stream's pipeline, below the HTTP/3 frame codec. A connection's streams are timed
 * together by its {@link Http3ConnectionWriteStallWatcher}, with the timeout its first stream was given.
 */
public final class Http3StreamWriteStallHandler extends ChannelOutboundHandlerAdapter {

    static final int MAX_WRITE_BYTES = 32 * 1024;

    private static final AttributeKey<Http3ConnectionWriteStallWatcher> CONNECTION_WATCHER =
        AttributeKey.valueOf(Http3ConnectionWriteStallWatcher.class, "CONNECTION_WATCHER");

    private final long timeoutMillis;
    private final MockServerLogger mockServerLogger;
    private final ChannelFutureListener written = future -> written(future.isSuccess());
    private Http3ConnectionWriteStallWatcher watcher;
    private Http3ConnectionWriteStallWatcher.Stream stream;
    private ArrayDeque<HeldWrite> held;
    private boolean passingOn;
    private boolean flushHeld;

    private record HeldWrite(Object msg, ChannelPromise promise) {
    }

    public Http3StreamWriteStallHandler(long timeoutMillis, MockServerLogger mockServerLogger) {
        this.timeoutMillis = timeoutMillis;
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        watcher = connectionWatcher(channel);
        stream = watcher.stream(() -> stalled(channel));
    }

    private Http3ConnectionWriteStallWatcher connectionWatcher(Channel channel) {
        Channel connection = channel.parent();
        if (!(connection instanceof QuicChannel)) {
            // not a QUIC stream, so there is no connection credit to share: timed on its own
            return new Http3ConnectionWriteStallWatcher(timeoutMillis, channel.eventLoop(), () -> 0L);
        }
        Attribute<Http3ConnectionWriteStallWatcher> attribute = connection.attr(CONNECTION_WATCHER);
        Http3ConnectionWriteStallWatcher connectionWatcher = attribute.get();
        if (connectionWatcher == null) {
            connectionWatcher = new Http3ConnectionWriteStallWatcher(timeoutMillis, connection.eventLoop(), () -> holderBytes(((QuicChannel) connection).peerTransportParameters()));
            attribute.set(connectionWatcher);
        }
        return connectionWatcher;
    }

    /**
     * The bytes a stream must have been sent before it could be what holds the connection's credit: half the smaller
     * of the client's initial connection and stream grants, as a client may return credit only once it has read half
     * a window. A stream sent less still has stream credit for its next write. 0, which leaves every stream to its
     * own clock, when the grant is unknown or half a window is under one write.
     */
    static long holderBytes(QuicTransportParameters granted) {
        if (granted == null) {
            return 0;
        }
        long halfWindow = Math.min(granted.initialMaxData(), granted.initialMaxStreamDataBidiLocal()) / 2;
        return halfWindow < MAX_WRITE_BYTES ? 0 : halfWindow;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        watcher.closed(stream);
        failHeld();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        promise = promise.unvoid();
        if (passingOn) {
            if (held == null) {
                held = new ArrayDeque<>();
            }
            held.addLast(new HeldWrite(msg, promise));
            return;
        }
        passingOn = true;
        try {
            passOn(ctx, msg, promise);
            for (HeldWrite next = nextHeld(); next != null; next = nextHeld()) {
                passOn(ctx, next.msg, next.promise);
            }
        } finally {
            passingOn = false;
            failHeld();
        }
        if (flushHeld) {
            flushHeld = false;
            ctx.flush();
        }
        watcher.watch(stream);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        if (held != null && !held.isEmpty()) {
            flushHeld = true;
        }
        ctx.flush();
    }

    private HeldWrite nextHeld() {
        return held == null ? null : held.pollFirst();
    }

    /**
     * Only a write that threw, or the handler being removed inside a write, leaves any.
     */
    private void failHeld() {
        for (HeldWrite next = nextHeld(); next != null; next = nextHeld()) {
            ReferenceCountUtil.release(next.msg);
            next.promise.tryFailure(new ClosedChannelException());
        }
    }

    private void passOn(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ByteBuf && ((ByteBuf) msg).readableBytes() > MAX_WRITE_BYTES) {
            writeInParts(ctx, (ByteBuf) msg, promise);
        } else {
            writePart(ctx, msg, promise);
        }
    }

    private void writeInParts(ChannelHandlerContext ctx, ByteBuf whole, ChannelPromise promise) {
        PromiseCombiner parts = new PromiseCombiner(ctx.executor());
        try {
            while (whole.isReadable()) {
                ChannelPromise partPromise = ctx.newPromise();
                writePart(ctx, whole.readRetainedSlice(Math.min(MAX_WRITE_BYTES, whole.readableBytes())), partPromise);
                parts.add((Future<Void>) partPromise);
            }
        } finally {
            whole.release();
        }
        parts.finish(promise);
    }

    private void writePart(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        watcher.writing(stream, bytes(msg));
        promise.addListener(written);
        ctx.write(msg, promise);
    }

    private static int bytes(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        return msg instanceof ByteBufHolder ? ((ByteBufHolder) msg).content().readableBytes() : 0;
    }

    private void written(boolean taken) {
        watcher.written(stream, taken);
    }

    private void stalled(Channel channel) {
        if (!channel.isActive()) {
            return;
        }
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("resetting HTTP/3 stream:{}because its client took none of the response waiting for it for:{}ms (responseWriteStallTimeoutMillis), so the response ends incomplete")
                    .setArguments(channel, watcher.timeoutMillis())
            );
        }
        Metrics.incrementResponseWriteStalls(Metrics.ResponseWriteStall.HTTP3_STREAM);
        ServerClosedChannels.markClosed(channel);
        if (channel instanceof QuicStreamChannel) {
            ((QuicStreamChannel) channel).shutdownOutput(Http3ErrorCode.H3_INTERNAL_ERROR.code());
        }
        // closing fails, and so releases, the writes QUIC has not taken
        channel.close();
    }
}

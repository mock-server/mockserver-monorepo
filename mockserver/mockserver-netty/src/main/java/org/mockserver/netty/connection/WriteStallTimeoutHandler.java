package org.mockserver.netty.connection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameEncoder;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.netty.proxy.relay.UpstreamProxyRelayHandler;
import org.slf4j.event.Level;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Closes an inbound connection when bytes have been waiting for its client for {@code responseWriteStallTimeoutMillis}
 * and the client has taken none of them, so a client that stops reading cannot hold what is queued for it (and, for a
 * streamed response, its upstream connection) indefinitely. Closing ends the response in progress incomplete.
 * <p>
 * Progress is the socket accepting more of the connection's outbound buffer. A slow reader's socket accepts bytes in
 * bursts, as the kernel wakes a writer only once a share of its send buffer is free, so the timeout must sit well above
 * that gap. On the native epoll transport the kernel's own record of when it last sent data to the client counts as
 * progress too, which removes the burst gap there.
 * <p>
 * Installed first when the connection is accepted and kept for its life, so it also bounds a stalled tunnel or WebSocket
 * client. Inspecting the outbound buffer costs nothing per write; a timer runs only while a flush has left bytes waiting.
 */
public final class WriteStallTimeoutHandler extends ChannelOutboundHandlerAdapter implements Runnable {

    private static final AttributeKey<Boolean> EXEMPT = AttributeKey.valueOf("MOCKSERVER_WRITE_STALL_EXEMPT");
    private static final boolean EPOLL_CLASSES_PRESENT = epollClassesPresent();

    private final long timeoutMillis;
    private final long timeoutNanos;
    private final long checkIntervalMillis;
    private final MockServerLogger mockServerLogger;
    private ChannelHandlerContext ctx;
    private ScheduledFuture<?> check;
    private Object lastCurrent;
    private long lastCurrentProgress;
    private long lastPendingBytes;
    private long lastCheckNanos;
    private long lastProgressNanos;

    public WriteStallTimeoutHandler(long timeoutMillis, MockServerLogger mockServerLogger) {
        this.timeoutMillis = timeoutMillis;
        this.timeoutNanos = MILLISECONDS.toNanos(timeoutMillis);
        this.checkIntervalMillis = checkIntervalMillis(timeoutMillis);
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * How often a timer checks for progress: often enough that a stall is noticed soon after the timeout, never more
     * than once a second.
     */
    public static long checkIntervalMillis(long timeoutMillis) {
        return Math.max(10L, Math.min(1000L, timeoutMillis / 2));
    }

    /**
     * Stop watching a connection whose silences are MockServer's own backpressure rather than its client's: the loopback
     * leg of a CONNECT/SOCKS relay is read only as fast as the relay's proxy client takes what it is sent, and that
     * client's own connection is watched. Must be called on the connection's event loop.
     */
    public static void exempt(Channel channel) {
        channel.attr(EXEMPT).set(Boolean.TRUE);
        WriteStallTimeoutHandler handler = channel.pipeline().get(WriteStallTimeoutHandler.class);
        if (handler != null) {
            channel.pipeline().remove(handler);
        }
    }

    public static boolean isExempt(Channel channel) {
        return channel.hasAttr(EXEMPT) && Boolean.TRUE.equals(channel.attr(EXEMPT).get());
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        disarm();
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        ctx.flush();
        if (check == null) {
            ChannelOutboundBuffer buffer = ctx.channel().unsafe().outboundBuffer();
            if (buffer != null && buffer.current() != null) {
                recordProgress(buffer);
                lastCheckNanos = System.nanoTime();
                lastProgressNanos = lastCheckNanos;
                check = ctx.executor().schedule(this, checkIntervalMillis, MILLISECONDS);
            }
        }
    }

    @Override
    public void run() {
        check = null;
        Channel channel = ctx.channel();
        ChannelOutboundBuffer buffer = channel.isActive() ? channel.unsafe().outboundBuffer() : null;
        if (buffer == null || buffer.current() == null) {
            // nothing is waiting for the client; the next flush that leaves bytes waiting starts watching again
            lastCurrent = null;
            return;
        }
        long now = System.nanoTime();
        if (recordProgress(buffer) || kernelSentDataSince(channel, now - lastCheckNanos)) {
            lastProgressNanos = now;
        } else if (now - lastProgressNanos >= timeoutNanos) {
            lastCurrent = null;
            stalled(channel, buffer.totalPendingWriteBytes());
            return;
        }
        lastCheckNanos = now;
        check = ctx.executor().schedule(this, checkIntervalMillis, MILLISECONDS);
    }

    /**
     * @return whether the socket has taken bytes since the last call: the message at the head of the buffer changed,
     * part of it was written, or fewer bytes are waiting (new writes alone increase the count and are not progress)
     */
    private boolean recordProgress(ChannelOutboundBuffer buffer) {
        Object current = buffer.current();
        long currentProgress = buffer.currentProgress();
        long pendingBytes = buffer.totalPendingWriteBytes();
        boolean progressed = current != lastCurrent || currentProgress != lastCurrentProgress || pendingBytes < lastPendingBytes;
        lastCurrent = current;
        lastCurrentProgress = currentProgress;
        lastPendingBytes = pendingBytes;
        return progressed;
    }

    private static boolean kernelSentDataSince(Channel channel, long sinceNanos) {
        return EPOLL_CLASSES_PRESENT && EpollTcpInfo.sentDataWithinMillis(channel, NANOSECONDS.toMillis(sinceNanos));
    }

    private void stalled(Channel channel, long pendingBytes) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("closing connection from:{}because its client took none of the{}bytes waiting for it for:{}ms (responseWriteStallTimeoutMillis), so the response in progress ends incomplete")
                    .setArguments(channel.remoteAddress(), pendingBytes, timeoutMillis)
            );
        }
        Metrics.incrementResponseWriteStalls(kind(channel.pipeline()));
        // The socket is closed directly, not through the pipeline: asked to close, a TLS handler first queues a
        // close_notify (and an HTTP/2 codec a GOAWAY) behind the bytes the client is not taking, and leaves the
        // connection open, refusing further writes, until that is flushed or its own timeout passes.
        ServerClosedChannels.markClosed(channel);
        channel.unsafe().close(channel.unsafe().voidPromise());
    }

    /**
     * What the connection carries, read from its pipeline when it is cut: a tunnel first, because a tunnel's proxy-client
     * pipeline also holds the HTTP/1.1 or HTTP/2 codec of the protocol it relays. A connection whose protocol is not yet
     * known (a TLS handshake, or a tunnel still reading its first bytes) or is not HTTP (binary proxying) is other.
     */
    private static Metrics.ResponseWriteStall kind(ChannelPipeline pipeline) {
        if (pipeline.get(UpstreamProxyRelayHandler.class) != null) {
            return Metrics.ResponseWriteStall.TUNNEL_CONNECTION;
        } else if (pipeline.get(Http2FrameCodec.class) != null) {
            return Metrics.ResponseWriteStall.HTTP2_CONNECTION;
        } else if (pipeline.get(WebSocketFrameEncoder.class) != null) {
            return Metrics.ResponseWriteStall.WEBSOCKET_CONNECTION;
        } else if (pipeline.get(HttpResponseEncoder.class) != null || pipeline.get(HttpServerCodec.class) != null) {
            return Metrics.ResponseWriteStall.HTTP1_CONNECTION;
        } else {
            return Metrics.ResponseWriteStall.OTHER_CONNECTION;
        }
    }

    private void disarm() {
        if (check != null) {
            check.cancel(false);
            check = null;
        }
        lastCurrent = null;
    }

    /**
     * Touches the epoll classes only once they are known to be present.
     */
    private static final class EpollTcpInfo {

        static boolean sentDataWithinMillis(Channel channel, long millis) {
            if (!(channel instanceof io.netty.channel.epoll.EpollSocketChannel)) {
                return false;
            }
            try {
                return ((io.netty.channel.epoll.EpollSocketChannel) channel).tcpInfo().lastDataSent() <= millis;
            } catch (RuntimeException unavailable) {
                return false;
            }
        }
    }

    private static boolean epollClassesPresent() {
        try {
            Class.forName("io.netty.channel.epoll.EpollSocketChannel", false, WriteStallTimeoutHandler.class.getClassLoader());
            return true;
        } catch (Throwable notPresent) {
            return false;
        }
    }
}

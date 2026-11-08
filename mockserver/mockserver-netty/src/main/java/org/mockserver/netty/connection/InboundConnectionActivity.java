package org.mockserver.netty.connection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import org.mockserver.socket.tls.SniHandler;

/**
 * What an inbound connection is doing, so {@link InboundConnectionIdleHandler} closes only connections
 * that are genuinely quiet. Present on a channel only while an idle timeout is configured; every
 * static helper is a no-op on a channel without it.
 * <p>
 * The exchange count is touched only on the connection's event loop ({@link HttpExchangeTracker}
 * runs there, and so do write-completion listeners), so it needs no synchronisation.
 */
public final class InboundConnectionActivity {

    static final AttributeKey<InboundConnectionActivity> ACTIVITY = AttributeKey.valueOf("MOCKSERVER_INBOUND_CONNECTION_ACTIVITY");

    private int httpExchangesInProgress;
    private boolean informationalResponseHeadWritten;
    private volatile boolean longLived;
    private final ChannelFutureListener httpExchangeCompletedListener = future -> httpExchangeCompleted();

    static InboundConnectionActivity of(Channel channel) {
        // hasAttr first: attr() would allocate the attribute on every channel that has no idle timeout
        return channel.hasAttr(ACTIVITY) ? channel.attr(ACTIVITY).get() : null;
    }

    /**
     * Whether an idle timeout is watching this connection, and so whether its HTTP exchanges need counting.
     */
    public static boolean isTracked(Channel channel) {
        InboundConnectionActivity activity = of(channel);
        return activity != null && !activity.longLived;
    }

    /**
     * Exempt a connection from the idle timeout for the rest of its life: it has become a WebSocket, a
     * raw binary relay or the loopback leg of a tunnel, whose silences are legitimate and whose traffic
     * is not HTTP exchanges. The idle handler and exchange tracker are then removed, so its traffic no
     * longer pays for them. The client's leg of a CONNECT/SOCKS tunnel is not exempt while it carries HTTP, but is
     * once its first bytes make it a binary connection.
     */
    public static void markLongLived(Channel channel) {
        InboundConnectionActivity activity = of(channel);
        if (activity != null) {
            activity.becomeLongLived(channel);
        }
    }

    void httpExchangeStarted() {
        httpExchangesInProgress++;
    }

    void httpExchangeCompleted() {
        if (httpExchangesInProgress > 0) {
            httpExchangesInProgress--;
        }
    }

    boolean informationalResponseHeadWritten() {
        return informationalResponseHeadWritten;
    }

    void informationalResponseHeadWritten(boolean written) {
        this.informationalResponseHeadWritten = written;
    }

    void becomeLongLived(Channel channel) {
        if (longLived) {
            return;
        }
        longLived = true;
        if (channel.isRegistered()) {
            channel.eventLoop().execute(() -> {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(InboundConnectionIdleHandler.class) != null) {
                    pipeline.remove(InboundConnectionIdleHandler.class);
                }
                if (pipeline.get(HttpExchangeTracker.class) != null) {
                    pipeline.remove(HttpExchangeTracker.class);
                }
            });
        }
    }

    ChannelFutureListener httpExchangeCompletedListener() {
        return httpExchangeCompletedListener;
    }

    /**
     * A connection is busy when anything could legitimately still be waiting on it. Auto-read being
     * off, a certificate being generated, or a TLS handshake in progress (bounded by its own handshake
     * timeout) mean the silence is MockServer's, not the client's.
     */
    boolean isBusy(Channel channel) {
        if (longLived || httpExchangesInProgress > 0 || !channel.config().isAutoRead()) {
            return true;
        }
        if (channel.hasAttr(SniHandler.SSL_CONTEXT_PENDING) && Boolean.TRUE.equals(channel.attr(SniHandler.SSL_CONTEXT_PENDING).get())) {
            return true;
        }
        ChannelPipeline pipeline = channel.pipeline();
        SslHandler sslHandler = pipeline.get(SslHandler.class);
        if (sslHandler != null && !sslHandler.handshakeFuture().isDone()) {
            return true;
        }
        Http2ConnectionHandler http2ConnectionHandler = pipeline.get(Http2ConnectionHandler.class);
        return http2ConnectionHandler != null && http2ConnectionHandler.connection().numActiveStreams() > 0;
    }
}

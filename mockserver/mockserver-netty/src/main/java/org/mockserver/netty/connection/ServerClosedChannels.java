package org.mockserver.netty.connection;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionAdapter;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.mockserver.model.StreamingBody;
import org.mockserver.socket.ConnectionClosing;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;

/**
 * Records that MockServer, not the client, ended a connection or stream, so a response writer can tell a client that
 * left from a close MockServer made itself (a write stall, a relay abort, a listener stop) when its channel closes
 * mid-response.
 */
public final class ServerClosedChannels {

    private static final AttributeKey<Boolean> CLOSED_BY_SERVER = AttributeKey.valueOf("mockserver.closedByServer");
    private static final AttributeKey<Boolean> TRANSPORT_ERROR = AttributeKey.valueOf("mockserver.transportError");
    private static final AttributeKey<Set<Integer>> RESET_HTTP2_STREAMS = AttributeKey.valueOf("mockserver.resetHttp2Streams");
    private static final AttributeKey<Set<Integer>> RELAYED_RESET_HTTP2_STREAMS = AttributeKey.valueOf("mockserver.relayedResetHttp2Streams");

    private ServerClosedChannels() {
    }

    /**
     * Call before MockServer closes a connection, an HTTP/2 or HTTP/3 stream, an HTTP/3 listener, or a tunnel's client
     * leg itself, including for an exception it closes the channel for; not for a client's close or reset.
     */
    public static void markClosed(Channel channel) {
        channel.attr(CLOSED_BY_SERVER).set(Boolean.TRUE);
    }

    /**
     * Call when a connection's transport reports an error it found itself, which it closes the connection for outside
     * the pipeline, as QUIC does: a close of the connection after it, other than a QUIC idle timeout, is then taken as
     * MockServer's, while a stream the client ends as the connection stays open is not.
     */
    public static void markTransportError(Channel connection) {
        connection.attr(TRANSPORT_ERROR).set(Boolean.TRUE);
    }

    /**
     * Call when the client closes a connection {@link #markTransportError} marked, which shows that its transport
     * did not close the connection for the error.
     */
    public static void clearTransportError(Channel connection) {
        if (connection.hasAttr(TRANSPORT_ERROR)) {
            connection.attr(TRANSPORT_ERROR).set(null);
        }
    }

    /**
     * Call before resetting an HTTP/2 stream on a write stall, having called {@link #forgetHttp2StreamResetsOnClose}
     * once for the connection.
     *
     * @param connection the HTTP/2 connection's channel
     */
    public static void markHttp2StreamReset(Channel connection, int streamId) {
        streamIds(connection, RESET_HTTP2_STREAMS, true).add(streamId);
    }

    /**
     * Forgets the reset of each stream of the connection once the stream has closed, so a long-lived connection holds
     * only those of streams still open.
     *
     * @param channel the HTTP/2 connection's channel
     */
    public static void forgetHttp2StreamResetsOnClose(Channel channel, Http2Connection connection) {
        forgetOnStreamClose(channel, connection, channel, RESET_HTTP2_STREAMS, null);
    }

    /**
     * On MockServer's end of a CONNECT/SOCKS relay loopback: forgets a reset the relay passed on for a loopback stream
     * ({@link #markRelayedHttp2StreamReset}) once the stream has closed here, and, as any stream closes, one marked for
     * a stream that had already closed here (the relay reset it before reading the end of its response). Does nothing
     * for any other connection.
     *
     * @param channel the HTTP/2 connection's channel
     */
    public static void forgetRelayedHttp2StreamResetsOnClose(Channel channel, Http2Connection connection) {
        Channel proxyClient = TunnelProxyClient.of(channel);
        if (proxyClient != null) {
            forgetOnStreamClose(channel, connection, proxyClient, RELAYED_RESET_HTTP2_STREAMS, id -> connection.stream(id) == null && connection.streamMayHaveExisted(id));
        }
    }

    /**
     * @param closedEarlier which other held ids to forget as a stream closes, or null for none
     */
    private static void forgetOnStreamClose(Channel channel, Http2Connection connection, Channel holder, AttributeKey<Set<Integer>> key, Predicate<Integer> closedEarlier) {
        connection.addListener(new Http2ConnectionAdapter() {
            @Override
            public void onStreamClosed(Http2Stream stream) {
                Set<Integer> ids = streamIds(holder, key, false);
                Integer streamId = stream.id();
                if (ids != null && (ids.contains(streamId) || (closedEarlier != null && !ids.isEmpty()))) {
                    Runnable forget = () -> {
                        ids.remove(streamId);
                        if (closedEarlier != null) {
                            ids.removeIf(closedEarlier);
                        }
                    };
                    // later, so a stream channel that closes after its stream can still tell
                    try {
                        channel.eventLoop().execute(forget);
                    } catch (RejectedExecutionException shuttingDown) {
                        forget.run();
                    }
                }
            }
        });
    }

    /**
     * Close the upstream of a stream whose client channel has closed, noting that the client ended the stream unless
     * MockServer closed the channel itself.
     */
    public static void closeUpstream(Channel channel, StreamingBody streamingBody) {
        if (isClosedByServer(channel)) {
            streamingBody.closeUpstream();
        } else {
            streamingBody.closeUpstreamForClientClose();
        }
    }

    /**
     * Call when the relay of a CONNECT/SOCKS tunnel passes on a reset MockServer made on the tunnel's client leg, with
     * the id of the loopback stream it resets, so the response writer on the loopback's far end can tell.
     */
    public static void markRelayedHttp2StreamReset(Channel proxyClient, int loopbackStreamId) {
        streamIds(proxyClient, RELAYED_RESET_HTTP2_STREAMS, true).add(loopbackStreamId);
    }

    /**
     * @return whether MockServer reset this HTTP/2 stream of the connection, which is then no longer held
     */
    public static boolean consumeHttp2StreamReset(Channel connection, int streamId) {
        Set<Integer> reset = streamIds(connection, RESET_HTTP2_STREAMS, false);
        return reset != null && reset.remove(streamId);
    }

    private static Set<Integer> streamIds(Channel channel, AttributeKey<Set<Integer>> key, boolean create) {
        if (!create) {
            return channel.hasAttr(key) ? channel.attr(key).get() : null;
        }
        Attribute<Set<Integer>> attribute = channel.attr(key);
        Set<Integer> ids = attribute.get();
        if (ids == null) {
            ids = ConcurrentHashMap.newKeySet();
            Set<Integer> existing = attribute.setIfAbsent(ids);
            if (existing != null) {
                ids = existing;
            }
        }
        return ids;
    }

    private static boolean isMarked(Channel channel) {
        return (channel.hasAttr(CLOSED_BY_SERVER) && Boolean.TRUE.equals(channel.attr(CLOSED_BY_SERVER).get())) || ConnectionClosing.isMarked(channel) || isClosedForTransportError(channel);
    }

    private static boolean isClosedForTransportError(Channel channel) {
        // QUIC marks itself closed before it closes the connection's streams, and sets its timeout flag first
        return channel.hasAttr(TRANSPORT_ERROR) && Boolean.TRUE.equals(channel.attr(TRANSPORT_ERROR).get()) && !channel.isOpen()
            && !(channel instanceof QuicChannel && ((QuicChannel) channel).isTimedOut());
    }

    private static boolean isEventLoopShuttingDown(Channel channel) {
        EventLoop eventLoop = channel.eventLoop();
        return eventLoop != null && eventLoop.isShuttingDown();
    }

    /**
     * @return whether MockServer closed this channel, any connection or listener it runs on, a tunnel's client leg, or
     * (for an HTTP/2 stream) the stream itself, or QUIC closed a connection it runs on for a transport error, or
     * MockServer is shutting down the event loop the channel runs on
     */
    public static boolean isClosedByServer(Channel channel) {
        // stop() calls shutdownGracefully before Netty closes the channels, so a close it causes is seen here
        if (isEventLoopShuttingDown(channel)) {
            return true;
        }
        Channel proxyClient = null;
        for (Channel current = channel; current != null; current = current.parent()) {
            if (isMarked(current)) {
                return true;
            }
            if (proxyClient == null) {
                proxyClient = TunnelProxyClient.of(current);
            }
        }
        if (proxyClient != null && isMarked(proxyClient)) {
            return true;
        }
        if (channel instanceof Http2StreamChannel && channel.parent() != null) {
            int streamId = ((Http2StreamChannel) channel).stream().id();
            if (consumeHttp2StreamReset(channel.parent(), streamId)) {
                return true;
            }
            if (proxyClient != null) {
                Set<Integer> relayed = streamIds(proxyClient, RELAYED_RESET_HTTP2_STREAMS, false);
                return relayed != null && relayed.remove(streamId);
            }
        }
        return false;
    }
}

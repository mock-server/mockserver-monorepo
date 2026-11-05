package org.mockserver.netty.connection;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.mockserver.model.StreamingBody;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records that MockServer, not the client, ended a connection or stream, so a response writer can tell a client that
 * left from a write stall abort when its channel closes mid-response.
 */
public final class ServerClosedChannels {

    private static final AttributeKey<Boolean> CLOSED_BY_SERVER = AttributeKey.valueOf("mockserver.closedByServer");
    private static final AttributeKey<Set<Integer>> RESET_HTTP2_STREAMS = AttributeKey.valueOf("mockserver.resetHttp2Streams");

    private ServerClosedChannels() {
    }

    /**
     * Call before closing a connection, or an HTTP/3 stream, on a write stall.
     */
    public static void markClosed(Channel channel) {
        channel.attr(CLOSED_BY_SERVER).set(Boolean.TRUE);
    }

    /**
     * Call before resetting an HTTP/2 stream on a write stall.
     *
     * @param connection the HTTP/2 connection's channel
     */
    public static void markHttp2StreamReset(Channel connection, int streamId) {
        Set<Integer> reset = connection.attr(RESET_HTTP2_STREAMS).get();
        if (reset == null) {
            reset = ConcurrentHashMap.newKeySet();
            Set<Integer> existing = connection.attr(RESET_HTTP2_STREAMS).setIfAbsent(reset);
            if (existing != null) {
                reset = existing;
            }
        }
        reset.add(streamId);
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

    private static boolean isMarked(Channel channel) {
        Attribute<Boolean> attribute = channel.attr(CLOSED_BY_SERVER);
        return attribute != null && Boolean.TRUE.equals(attribute.get());
    }

    private static boolean isEventLoopShuttingDown(Channel channel) {
        EventLoop eventLoop = channel.eventLoop();
        return eventLoop != null && eventLoop.isShuttingDown();
    }

    /**
     * @return whether MockServer closed this channel, its connection, or (for an HTTP/2 stream) the stream itself, or
     * is shutting down the event loop the channel runs on
     */
    public static boolean isClosedByServer(Channel channel) {
        // stop() calls shutdownGracefully before Netty closes the channels, so a close it causes is seen here
        if (isMarked(channel) || isEventLoopShuttingDown(channel)) {
            return true;
        }
        Channel parent = channel.parent();
        if (parent == null) {
            return false;
        }
        if (isMarked(parent)) {
            return true;
        }
        if (channel instanceof Http2StreamChannel) {
            Attribute<Set<Integer>> attribute = parent.attr(RESET_HTTP2_STREAMS);
            Set<Integer> reset = attribute == null ? null : attribute.get();
            return reset != null && reset.remove(((Http2StreamChannel) channel).stream().id());
        }
        return false;
    }
}

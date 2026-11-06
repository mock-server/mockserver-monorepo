package org.mockserver.netty.connection;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.mockserver.model.StreamingBody;
import org.mockserver.netty.proxy.relay.RelayLoopbackAddresses;
import org.mockserver.socket.ConnectionClosing;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records that MockServer, not the client, ended a connection or stream, so a response writer can tell a client that
 * left from a close MockServer made itself (a write stall, a relay abort, a listener stop) when its channel closes
 * mid-response.
 */
public final class ServerClosedChannels {

    private static final AttributeKey<Boolean> CLOSED_BY_SERVER = AttributeKey.valueOf("mockserver.closedByServer");
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
     * Call before resetting an HTTP/2 stream on a write stall.
     *
     * @param connection the HTTP/2 connection's channel
     */
    public static void markHttp2StreamReset(Channel connection, int streamId) {
        streamIds(connection, RESET_HTTP2_STREAMS, true).add(streamId);
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
        Attribute<Set<Integer>> attribute = channel.attr(key);
        if (attribute == null) {
            return null;
        }
        Set<Integer> ids = attribute.get();
        if (ids == null && create) {
            ids = ConcurrentHashMap.newKeySet();
            Set<Integer> existing = attribute.setIfAbsent(ids);
            if (existing != null) {
                ids = existing;
            }
        }
        return ids;
    }

    private static boolean isMarked(Channel channel) {
        Attribute<Boolean> attribute = channel.attr(CLOSED_BY_SERVER);
        return (attribute != null && Boolean.TRUE.equals(attribute.get())) || ConnectionClosing.isMarked(channel);
    }

    private static boolean isEventLoopShuttingDown(Channel channel) {
        EventLoop eventLoop = channel.eventLoop();
        return eventLoop != null && eventLoop.isShuttingDown();
    }

    /**
     * @return whether MockServer closed this channel, any connection or listener it runs on, a tunnel's client leg, or
     * (for an HTTP/2 stream) the stream itself, or is shutting down the event loop the channel runs on
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
                proxyClient = RelayLoopbackAddresses.rememberedProxyClient(current);
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

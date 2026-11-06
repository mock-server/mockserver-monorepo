package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.DuplexChannel;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.socket.LingeringClose;
import org.mockserver.socket.ReadAfterFailedWrite;

import static org.mockserver.socket.LingeringClose.closeSocket;

/**
 * Closes one leg of a CONNECT/SOCKS relay at its socket, not through its pipeline, once the relay has no further use
 * for it. Asked to close, an HTTP/2 connection handler sends a {@code GOAWAY} and keeps the connection open while it has
 * active streams, for up to Netty's graceful-shutdown timeout (30 s), and a TLS handler waits for its
 * {@code close_notify} to be taken. Closing the socket fails what is queued and fires {@code channelInactive}.
 */
final class RelayLegClose {

    private RelayLegClose() {
    }

    static void now(Channel channel) {
        if (channel.eventLoop().inEventLoop()) {
            closeSocket(channel);
        } else {
            channel.eventLoop().execute(() -> closeSocket(channel));
        }
    }

    /**
     * Ends the leg once its outbound buffer has been flushed (HTTP/2 DATA waiting for flow-control window is not in it
     * and fails, so a caller waits for such a write first). A socket's output is shut down and the socket closed when
     * the other end closes, as it does on reading the end of the stream, or {@link LingeringClose#LINGER_MILLIS} after
     * this is called, flushed or not: an other end that has stopped reading never lets the flush complete.
     * Closed at once it would reset whatever the other end writes next, and MockServer's end of a loopback writes
     * while it reads (its HTTP/2 settings): a write that fails closes that end with what was flushed here unread.
     */
    static void afterFlush(Channel channel) {
        if (channel.isActive()) {
            LingeringClose.closeSocketUnlessClosedWithinLinger(channel);
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(future -> endOutput(channel));
        } else {
            now(channel);
        }
    }

    /**
     * As {@link #afterFlush}, once this write, the last the leg is given, has completed.
     */
    static void afterWritten(ChannelFuture lastWrite) {
        LingeringClose.closeSocketUnlessClosedWithinLinger(lastWrite.channel());
        lastWrite.addListener(future -> endOutput(lastWrite.channel()));
    }

    /**
     * Marks a tunnel's client leg as closed by MockServer, unless its client has already gone: the leg is closed, or a
     * failed write has ended its output ({@link ReadAfterFailedWrite}).
     */
    static void markClientLegClosedByServer(Channel clientLeg) {
        if (clientLeg.isActive() && !ReadAfterFailedWrite.isReadingOn(clientLeg)) {
            ServerClosedChannels.markClosed(clientLeg);
        }
    }

    private static void endOutput(Channel channel) {
        if (!(channel instanceof DuplexChannel) || !channel.isActive()) {
            closeSocket(channel);
            return;
        }
        // at the socket, as the close is: through the pipeline a TLS handler would first write its close_notify
        ReadAfterFailedWrite.endOutput((DuplexChannel) channel);
    }
}

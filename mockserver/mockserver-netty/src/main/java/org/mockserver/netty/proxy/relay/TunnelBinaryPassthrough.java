package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.ChannelReadPause;
import org.slf4j.event.Level;

import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;
import static org.mockserver.exception.ExceptionHandling.clientGoneException;

/**
 * The relay of a CONNECT or SOCKS tunnel whose client speaks neither TLS nor HTTP: its bytes reach MockServer's end of
 * the loopback as they were sent, where they are a binary connection to the tunnel's destination, and what MockServer
 * writes back reaches the client as it was written. While one leg cannot take more, the other is not read. Closing
 * either leg closes the other, as {@link RelayConnectHandler} arranges for every tunnel.
 */
final class TunnelBinaryPassthrough extends ChannelInboundHandlerAdapter {

    private final Channel own;
    private final Channel other;
    private final MockServerLogger mockServerLogger;
    private final String leg;
    // the other leg's handler; both legs share one event loop, so neither needs synchronising
    private TunnelBinaryPassthrough peer;
    private boolean readsPaused;

    private TunnelBinaryPassthrough(Channel own, Channel other, MockServerLogger mockServerLogger, String leg) {
        this.own = own;
        this.other = other;
        this.mockServerLogger = mockServerLogger;
        this.leg = leg;
    }

    static void install(Channel proxyClient, Channel loopback, MockServerLogger mockServerLogger) {
        TunnelBinaryPassthrough fromClient = new TunnelBinaryPassthrough(proxyClient, loopback, mockServerLogger, "client");
        TunnelBinaryPassthrough fromLoopback = new TunnelBinaryPassthrough(loopback, proxyClient, mockServerLogger, "loopback");
        fromClient.peer = fromLoopback;
        fromLoopback.peer = fromClient;
        proxyClient.pipeline().addLast(fromClient);
        loopback.pipeline().addLast(fromLoopback);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        other.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        if (!other.isWritable() && !readsPaused) {
            readsPaused = true;
            ChannelReadPause.pause(own);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable()) {
            peer.resumeReads();
        }
        ctx.fireChannelWritabilityChanged();
    }

    private void resumeReads() {
        if (readsPaused) {
            readsPaused = false;
            ChannelReadPause.resume(own);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Level level = clientGoneException(cause) ? Level.DEBUG : Level.WARN;
        if (mockServerLogger.isEnabledForInstance(level)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(level)
                    .setMessageFormat("closing binary tunnel's{}connection " + ctx.channel() + " for:{}")
                    .setArguments(leg, boundedFaultMessage(cause))
            );
        }
        ctx.close();
    }
}

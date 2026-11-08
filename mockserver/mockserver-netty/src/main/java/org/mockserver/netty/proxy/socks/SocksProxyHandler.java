package org.mockserver.netty.proxy.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;
import static org.mockserver.netty.HttpRequestHandler.setProxyingRequest;
import static org.mockserver.netty.unification.PortUnificationHandler.deferTlsDetection;

@ChannelHandler.Sharable
public abstract class SocksProxyHandler<T> extends SimpleChannelInboundHandler<T> {

    protected final Configuration configuration;
    protected final LifeCycle server;
    protected final MockServerLogger mockServerLogger;

    public SocksProxyHandler(Configuration configuration, MockServerLogger mockServerLogger, LifeCycle server) {
        super(false);
        this.configuration = configuration;
        this.server = server;
        this.mockServerLogger = mockServerLogger;
    }

    protected void forwardConnection(final ChannelHandlerContext ctx, ChannelHandler forwarder, final String addr, int port) {
        Channel channel = ctx.channel();
        setProxyingRequest(ctx, Boolean.TRUE);
        // The destination port is the only signal available here, before the client's ClientHello, and it
        // is wrong in both directions: TLS is routinely served on ports that do not end in 443 (8080-style
        // custom ports) and cleartext can be served on a 443-suffix port. Rather than guess, mark the
        // tunnel for byte-driven detection: the relay classifies the first tunnelled bytes (TLS record vs
        // cleartext HTTP) after the SOCKS reply is sent, and provisions the loopback to match - reading the
        // real ALPN result for TLS. This removes the non-443 gap left by the port heuristic (issue #2685).
        deferTlsDetection(channel);

        // add Subject Alternative Name for SSL certificate
        if (isNotBlank(addr)) {
            server.getScheduler().submit(() -> configuration.addSubjectAlternativeName(addr));
        }

        ctx.pipeline().replace(this, null, forwarder);
    }

    /**
     * Refuses the handshake: writes {@code reply} and closes the connection. Whatever the client sent after the refused
     * message is discarded, so it cannot reach the protocol detection behind this handler.
     */
    protected void refuse(ChannelHandlerContext ctx, Object reply) {
        ctx.pipeline().addAfter(ctx.name(), null, new DiscardAfterRefusal());
        ctx.writeAndFlush(reply).addListener(ChannelFutureListener.CLOSE);
    }

    private static final class DiscardAfterRefusal extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
        // a mid-pipeline handler that swallows channelReadComplete starves Netty's HTTP/2
        // flow-control flush (Http2ConnectionHandler.channelReadComplete -> writePendingBytes),
        // stalling any h2 response larger than the peer's initial window - so propagate the event
        ctx.fireChannelReadComplete();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by SOCKS proxy handler -> closing pipeline " + ctx.channel())
                    .setThrowable(cause)
            );
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("SSL or decoder fault caught by SOCKS proxy handler -> closing pipeline " + ctx.channel() + sniDescription(ctx.channel()))
                        .setThrowable(boundedFault(cause))
                );
            }
        }
        ctx.close();
    }
}

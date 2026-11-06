package org.mockserver.netty.unification;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodec;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.slf4j.event.Level;

import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;

/**
 * The last handler of a direct HTTP/2 connection's pipeline. It logs each exception that reaches the end of that
 * pipeline once, at the level its cause calls for, where Netty would log every one at {@code WARN} with a stack trace
 * through its own logger.
 * <p>
 * It must stay last: {@code Http2MultiplexHandler}, before it, hands a stream's errors to that stream. It does not
 * close the connection for an HTTP/2 connection error, which Netty's codec fires here before it sends the
 * {@code GOAWAY} and closes: closing here would lose the {@code GOAWAY}. For an exception it does not recognise it
 * closes with {@code GOAWAY(INTERNAL_ERROR)}, as the HTTP/1.1 handlers close for any exception.
 */
public class Http2ConnectionExceptionHandler extends ChannelInboundHandlerAdapter {

    /**
     * How the connection is closed for an exception.
     */
    enum Close {
        NOT_HERE, NOW, WITH_GOAWAY
    }

    private final MockServerLogger mockServerLogger;

    public Http2ConnectionExceptionHandler(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Close close = log(mockServerLogger, ctx, cause);
        if (close == Close.NOW) {
            // seen by each stream through its parent, as the streams close with the connection
            ServerClosedChannels.markClosed(ctx.channel());
            ctx.close();
        } else if (close == Close.WITH_GOAWAY) {
            // later: the codec fires an exception it caught decoding here before it sends its own GOAWAY for it
            ctx.channel().eventLoop().execute(() -> closeWithGoAway(ctx));
        }
    }

    /**
     * Logs an exception on an HTTP/2 connection once, as this handler logs what reaches it; also called for a tunnel's
     * client leg, whose handler fires no connection error down its pipeline.
     *
     * @return how this handler closes the connection for it: at once for an SSL or decoder fault or the direct memory
     * limit, with {@code GOAWAY(INTERNAL_ERROR)} for an exception it does not recognise, and not at all otherwise
     */
    static Close log(MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Throwable cause) {
        Http2Exception connectionError = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
        if (directMemoryLimitReached(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + cause.getMessage())
            );
            return Close.NOW;
        } else if (connectionError != null) {
            // a request refused for its header size was logged where it was refused
            if (!Http2RequestHeaderLimit.isRefusal(connectionError) && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("closing HTTP/2 connection from:{}for connection error:{}")
                        .setArguments(ctx.channel().remoteAddress(), connectionError.error())
                        .setThrowable(cause)
                );
            }
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                // no throwable: its message is not bounded, as the one logged here is
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("closing HTTP/2 connection " + ctx.channel() + sniDescription(ctx.channel()) + " for SSL or decoder fault " + cause.getClass().getName() + ":{}")
                        .setArguments(boundedFaultMessage(cause))
                );
            }
            // Netty's JDK TLS handler leaves the connection open after such bytes and reports every read that follows
            return Close.NOW;
        } else if (connectionClosedException(cause)) {
            // despite its name, true for everything except a connection its peer closed or reset
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("closing HTTP/2 connection " + ctx.channel() + " for unexpected exception")
                    .setThrowable(cause)
            );
            return Close.WITH_GOAWAY;
        } else if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("HTTP/2 connection from:{}closed by its client:{}")
                    .setArguments(ctx.channel().remoteAddress(), cause.getMessage())
            );
        }
        return Close.NOT_HERE;
    }

    /**
     * Through the codec, which writes the {@code GOAWAY} after the frames already written, flushes and then closes.
     * None is written once the codec has sent one: its own, for a connection error, names a different last stream.
     */
    private static void closeWithGoAway(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            // ahead of the GOAWAY, which can close streams too
            ServerClosedChannels.markClosed(ctx.channel());
            Http2FrameCodec codec = ctx.pipeline().get(Http2FrameCodec.class);
            if (codec == null || !codec.connection().goAwaySent()) {
                ctx.write(new DefaultHttp2GoAwayFrame(Http2Error.INTERNAL_ERROR));
            }
            ctx.close();
        }
    }
}

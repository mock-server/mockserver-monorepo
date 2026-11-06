package org.mockserver.netty.http3;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http3.Http3Exception;
import io.netty.handler.codec.http3.Http3Settings;
import io.netty.handler.codec.http3.Http3SettingsFrame;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicException;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamResetException;
import io.netty.handler.codec.quic.QuicStreamType;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.netty.unification.ClientTlsHandshakeFailureLog;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;

import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;

/**
 * The last handler of an HTTP/3 connection's pipeline. It logs each exception that reaches the end of that pipeline
 * once, at the level its cause calls for, where Netty would log every one at {@code WARN} with a stack trace through
 * its own logger. It does the same for the streams Netty's HTTP/3 codec keeps for itself, the client's control and
 * QPACK streams, by adding a handler to each as the client opens it. The streams MockServer's side opens cannot be
 * reached, so on the client's control stream it keeps the SETTINGS within what Netty's QPACK encoder accepts.
 * <p>
 * It closes nothing but a channel that met Netty's direct memory limit: Netty closes the connection for a failed
 * handshake, a QUIC error and an HTTP/3 connection error, and neither a stream its client reset nor a frame Netty
 * could not decode closed anything before.
 */
@ChannelHandler.Sharable
public class Http3ExceptionHandler extends ChannelInboundHandlerAdapter {

    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private final ClientTlsHandshakeFailureLog clientTlsHandshakeFailureLog;
    // null on the handler of a stream
    private final Http3ExceptionHandler unidirectionalStreamHandler;

    /**
     * @return the handler for a QUIC connection's pipeline, where it must stay last
     */
    public static Http3ExceptionHandler forConnection(MockServerLogger mockServerLogger, Configuration configuration, ClientTlsHandshakeFailureLog clientTlsHandshakeFailureLog) {
        return new Http3ExceptionHandler(mockServerLogger, configuration, clientTlsHandshakeFailureLog, new Http3ExceptionHandler(mockServerLogger, configuration, clientTlsHandshakeFailureLog, null));
    }

    private Http3ExceptionHandler(MockServerLogger mockServerLogger, Configuration configuration, ClientTlsHandshakeFailureLog clientTlsHandshakeFailureLog, Http3ExceptionHandler unidirectionalStreamHandler) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
        this.clientTlsHandshakeFailureLog = clientTlsHandshakeFailureLog;
        this.unidirectionalStreamHandler = unidirectionalStreamHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (unidirectionalStreamHandler == null) {
            if (msg instanceof Http3SettingsFrame) {
                // on the control stream this sits ahead of Netty's handler, which acts on the SETTINGS
                limitQpackBlockedStreams(ctx, ((Http3SettingsFrame) msg).settings());
            }
        } else if (msg instanceof QuicStreamChannel && ((QuicStreamChannel) msg).type() == QuicStreamType.UNIDIRECTIONAL) {
            // not a request stream, whose own last handler takes its exceptions and would be added after this one
            ((QuicStreamChannel) msg).pipeline().addLast(unidirectionalStreamHandler);
        }
        // the end of the pipeline is where Netty registers a new stream
        ctx.fireChannelRead(msg);
    }

    /**
     * RFC 9204 lets a client allow up to 2^62-1 blocked streams, but Netty's QPACK encoder fails on more than an
     * {@code int} holds, on the encoder stream MockServer's side opens, which no MockServer handler can reach: Netty
     * would log it at {@code WARN} and close the connection. An encoder may always block fewer streams than allowed.
     */
    private void limitQpackBlockedStreams(ChannelHandlerContext ctx, Http3Settings settings) {
        Long blockedStreams = settings.qpackBlockedStreams();
        if (blockedStreams != null && blockedStreams > Integer.MAX_VALUE) {
            settings.qpackBlockedStreams(Integer.MAX_VALUE);
            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("HTTP/3 connection from:{}allows more blocked QPACK streams:{}than MockServer's encoder supports, which blocks at most:{}")
                        .setArguments(peerAddress(ctx.channel()), blockedStreams, Integer.MAX_VALUE)
                );
            }
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        // a client that rejects the handshake closes the connection with the TLS alert as its error, and fires no exception
        if (unidirectionalStreamHandler != null && evt instanceof QuicConnectionCloseEvent && ((QuicConnectionCloseEvent) evt).isTlsError()) {
            int alert = QuicConnectionCloseEvent.extractTlsError(((QuicConnectionCloseEvent) evt).error());
            clientTlsHandshakeFailureLog.logClientAlert(mockServerLogger, configuration, ClientTlsHandshakeFailureLog.HTTP3, peerAddress(ctx.channel()), alert);
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (directMemoryLimitReached(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + cause.getMessage())
            );
            // closing a stream Netty's codec keeps for itself closes its connection, and every request stream with it
            Channel parent = ctx.channel().parent();
            ServerClosedChannels.markClosed(unidirectionalStreamHandler == null && parent != null ? parent : ctx.channel());
            ctx.close();
        } else if (cause instanceof Http3Exception) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("closing HTTP/3 connection from:{}for connection error:{}")
                        .setArguments(peerAddress(ctx.channel()), ((Http3Exception) cause).errorCode())
                        .setThrowable(cause)
                );
            }
        } else if (cause instanceof SSLException) {
            clientTlsHandshakeFailureLog.log(mockServerLogger, configuration, ClientTlsHandshakeFailureLog.HTTP3, peerAddress(ctx.channel()), cause);
        } else if (isSslOrDecoderFault(cause)) {
            // ahead of the next branch, whose check takes every decoder fault for a closed connection
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat(unidirectionalStreamHandler == null
                            ? "SSL or decoder fault on stream of HTTP/3 connection from:{}:{}:{}"
                            : "SSL or decoder fault on HTTP/3 connection from:{}:{}:{}")
                        .setArguments(peerAddress(ctx.channel()), cause.getClass().getName(), boundedFaultMessage(cause))
                );
            }
        } else if (cause instanceof QuicStreamResetException || cause instanceof ClosedChannelException || !connectionClosedException(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat(unidirectionalStreamHandler == null
                            ? "stream of HTTP/3 connection from:{}closed or reset by its client:{}"
                            : "HTTP/3 connection from:{}closed or reset by its client:{}")
                        .setArguments(peerAddress(ctx.channel()), cause.getMessage())
                );
            }
        } else if (cause instanceof QuicException) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("QUIC error on HTTP/3 connection from:{}:{}")
                        .setArguments(peerAddress(ctx.channel()), boundedFaultMessage(cause))
                );
            }
        } else {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught on HTTP/3 connection " + ctx.channel())
                    .setThrowable(cause)
            );
        }
    }

    /**
     * The client's socket address: a QUIC channel's {@code remoteAddress()} is its connection id, and a stream's is
     * its stream id, so a QUIC stream reports only its connection's peer. Null when that is unknown, as for a stream
     * with no connection or a connection that has closed.
     */
    static SocketAddress peerAddress(Channel channel) {
        if (channel == null) {
            return null;
        }
        Channel connection = channel instanceof QuicStreamChannel ? channel.parent() : channel;
        if (connection instanceof QuicChannel) {
            return ((QuicChannel) connection).remoteSocketAddress();
        }
        return connection != null ? connection.remoteAddress() : null;
    }
}

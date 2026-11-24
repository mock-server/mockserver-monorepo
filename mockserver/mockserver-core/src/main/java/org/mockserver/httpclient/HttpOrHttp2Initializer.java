package org.mockserver.httpclient;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.util.function.Consumer;

import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sslCause;
import static org.mockserver.exception.ExceptionHandling.tlsFailure;
import static org.mockserver.exception.ExceptionHandling.upstreamHandshakeFailure;
import static org.mockserver.httpclient.NettyHttpClient.REMOTE_SOCKET;

public class HttpOrHttp2Initializer extends ApplicationProtocolNegotiationHandler {

    private final MockServerLogger mockServerLogger;
    private final Consumer<ChannelPipeline> http2Initializer;
    private final Consumer<ChannelPipeline> http1Initializer;
    // one per connection: the handshake's exception the waiting request was failed with
    private SSLException failedRequestWith;

    protected HttpOrHttp2Initializer(MockServerLogger mockServerLogger, Consumer<ChannelPipeline> http1Initializer, Consumer<ChannelPipeline> http2Initializer) {
        super("");
        this.mockServerLogger = mockServerLogger;
        this.http2Initializer = http2Initializer;
        this.http1Initializer = http1Initializer;
    }

    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
        ChannelPipeline pipeline = ctx.pipeline();
        if (pipeline.get(HttpOrHttp2Initializer.class) != null) {
            pipeline.remove(HttpOrHttp2Initializer.class);
        }
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
            http2Initializer.accept(pipeline);
        } else {
            http1Initializer.accept(pipeline);
        }
    }

    /**
     * A failed handshake is reported first as this event, and only as that for a timeout or a connection that
     * closed during it: the waiting request is failed with its exception here, before the connection closes and
     * its teardown is reported in its place.
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        SSLException handshakeFailure = HttpClientConnectionErrorHandler.handshakeFailure(evt);
        if (handshakeFailure != null) {
            failWaitingRequest(ctx, handshakeFailure);
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * Netty 4.2.21+ calls this for a handshake that timed out or whose connection closed, after the event above, and
     * by default logs it at {@code WARN}; the waiting request already failed with it, so it is not logged again.
     */
    @Override
    protected void handshakeFailure(ChannelHandlerContext ctx, Throwable cause) {
        SSLException handshakeFailure = tlsFailure(upstreamHandshakeFailure(cause));
        if (handshakeFailure != null) {
            failWaitingRequest(ctx, handshakeFailure);
        }
        ctx.close();
    }

    /**
     * @return whether the waiting request fails with {@code handshakeFailure}, and so is logged with it
     */
    private boolean failWaitingRequest(ChannelHandlerContext ctx, SSLException handshakeFailure) {
        if (handshakeFailure == failedRequestWith) {
            return true;
        }
        if (HttpClientConnectionErrorHandler.failWaitingRequestWithHandshakeFailure(ctx.channel(), handshakeFailure)) {
            failedRequestWith = handshakeFailure;
            return true;
        }
        return false;
    }

    /**
     * Until the protocol is negotiated this is the last handler of the pipeline. It logs what stopped the connection
     * being set up once and closes the connection, where Netty's handler logs it through its own logger at
     * {@code WARN} with a stack trace and, unless it is a failed TLS handshake, passes it on to be logged again.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (ForwardHeaderLimit.isAlreadyLogged(ctx.channel(), cause)) {
            // logged as the refusal that failed the forward
        } else if (directMemoryLimitReached(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + cause.getMessage())
            );
        } else if (isSslOrDecoderFault(cause) || sslCause(cause) != null) {
            SSLException handshakeFailure = tlsFailure(cause);
            // a request that fails with the cause is logged with it; otherwise this entry is where the reason is
            Level level = handshakeFailure != null && failWaitingRequest(ctx, handshakeFailure) ? Level.DEBUG : Level.WARN;
            if (mockServerLogger.isEnabledForInstance(level)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(level)
                        .setMessageFormat("TLS could not be set up on connection to:{}")
                        .setArguments(ctx.channel().attr(REMOTE_SOCKET).get())
                        .setThrowable(boundedFault(cause))
                );
            }
        } else if (cause instanceof ConnectException || !connectionClosedException(cause)) {
            // the forward was failed with this cause, and is logged where it fails
            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("connection to:{}failed or was closed before TLS was set up:{}")
                        .setArguments(ctx.channel().attr(REMOTE_SOCKET).get(), cause.getMessage())
                );
            }
        } else {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught before TLS was set up on connection to upstream " + ctx.channel())
                    .setThrowable(cause)
            );
        }
        ctx.close();
    }
}

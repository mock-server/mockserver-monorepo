package org.mockserver.netty.unification;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.util.AttributeKey;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.socket.ReadAfterFailedWrite;
import org.slf4j.event.Level;

/**
 * Limits the header list of an HTTP/2 request to {@code maxHeaderSize}, as HTTP/1.1 limits its header section, and
 * logs each request refused for it. The limit is counted as HTTP/2 defines it (RFC 9113 section 6.5.2): every field's
 * name and value, the pseudo-header fields included, plus 32 bytes a field, after HPACK decoding.
 * <p>
 * Netty enforces it: a header list over the limit is answered {@code 431} and its stream reset, and a header block
 * of more than the limit plus a quarter closes the connection with {@code GOAWAY}. Neither reaches a handler, so the
 * codecs built here log them as they are raised.
 * <p>
 * A request's trailers are limited as its headers are, and only reset their stream: the request is not dispatched,
 * and a response being sent stops. A tunnel answers {@code 431} first if no response has started; a direct
 * connection hands the error to the stream's own pipeline, where {@link LenientInboundHttp2StreamFrameCodec} stops it.
 */
public final class Http2RequestHeaderLimit {

    /**
     * The start of Netty's message for a header block over the limit plus a quarter, which is a plain connection
     * error: {@code Http2CodecUtil.headerListSizeExceeded(long)}.
     */
    static final String HEADER_BLOCK_TOO_LARGE = "Header size exceeded max allowed size";

    private static final AttributeKey<Boolean> TRAILERS_REFUSED = AttributeKey.valueOf("HTTP2_TRAILERS_REFUSED");

    private Http2RequestHeaderLimit() {
    }

    /**
     * @return Netty's default settings with {@code SETTINGS_MAX_HEADER_LIST_SIZE} set to {@code maxHeaderSize}
     */
    public static Http2Settings serverSettings(Configuration configuration) {
        return Http2Settings.defaultSettings().maxHeaderListSize(configuration.maxHeaderSize());
    }

    /**
     * @return the settings of both ends of the loopback leg of a CONNECT or SOCKS tunnel, which set no header list
     * limit. Its requests were limited on the tunnel's client leg and the relay has since added fields to them, so
     * limiting them again would refuse a request the client was told is within the limit; its responses are
     * MockServer's own
     */
    public static Http2Settings relayLoopbackSettings() {
        return Http2Settings.defaultSettings().maxHeaderListSize(Http2CodecUtil.MAX_HEADER_LIST_SIZE);
    }

    /**
     * @return the client handler of the loopback leg of a CONNECT or SOCKS tunnel carrying HTTP/2, built as Netty's
     * {@code HttpToHttp2ConnectionHandlerBuilder} builds it with {@link #relayLoopbackSettings()}, except that it sends
     * no {@code x-http2-} extension header a request read from the proxy client carries, and that it logs a connection
     * error it closes the loopback for, marking the tunnel's client leg {@code proxyClient} closed by MockServer
     */
    public static HttpToHttp2ConnectionHandler relayLoopbackHandler(MockServerLogger mockServerLogger, Channel proxyClient, Http2Connection connection, Http2FrameListener frameListener, Http2FrameLogger frameLogger) {
        return relayLoopbackHandlerBuilder(mockServerLogger, proxyClient, connection, frameListener, frameLogger).build();
    }

    static RelayLoopbackHandlerBuilder relayLoopbackHandlerBuilder(MockServerLogger mockServerLogger, Channel proxyClient, Http2Connection connection, Http2FrameListener frameListener, Http2FrameLogger frameLogger) {
        return new RelayLoopbackHandlerBuilder(mockServerLogger, proxyClient, connection, frameListener, frameLogger);
    }

    /**
     * @return a server {@link Http2FrameCodecBuilder} whose codec logs the requests it refuses for their header size
     */
    public static Http2FrameCodecBuilder frameCodecBuilder(MockServerLogger mockServerLogger) {
        return new Http2FrameCodecBuilder() {
            {
                // as Http2FrameCodecBuilder.forServer() sets them: without the zero, closing a connection with
                // streams open waits out Netty's 30 second graceful shutdown
                server(true);
                gracefulShutdownTimeoutMillis(0);
                // added while the client's first bytes are read: SETTINGS goes out with that read's
                // channelReadComplete, so a failed flush cannot close the channel before they are decoded
                flushPreface(false);
            }

            @Override
            protected Http2FrameCodec build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings) {
                Http2FrameCodec codec = new Http2FrameCodec(encoder, decoder, initialSettings, decoupleCloseAndGoAway(), flushPreface()) {
                    @Override
                    public void onError(ChannelHandlerContext ctx, boolean outbound, Throwable cause) {
                        logRefusalThen(mockServerLogger, ctx, initialSettings, outbound, cause, () -> super.onError(ctx, outbound, cause));
                    }

                    @Override
                    protected void onConnectionError(ChannelHandlerContext ctx, boolean outbound, Throwable cause, Http2Exception http2Ex) {
                        if (!isWriteFailedAsTheOutputEnded(ctx, outbound, http2Ex)) {
                            markClosedForConnectionError(ctx.channel(), outbound);
                            super.onConnectionError(ctx, outbound, cause, http2Ex);
                        }
                    }

                    @Override
                    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
                        closeOnceReadUnlessReadingOn(ctx, promise, () -> super.close(ctx, promise));
                    }
                };
                codec.gracefulShutdownTimeoutMillis(gracefulShutdownTimeoutMillis());
                return codec;
            }
        };
    }

    /**
     * @param addedWhileReading whether the handler is added while the client's bytes are being read, so that the
     *                          read's {@code channelReadComplete} flushes its {@code SETTINGS} once they are decoded;
     *                          otherwise they are flushed as the handler is added
     * @return the server handler of a CONNECT or SOCKS tunnel carrying HTTP/2, which logs the requests it refuses
     * for their header size
     */
    public static HttpToHttp2ConnectionHandler tunnelServerHandler(Configuration configuration, MockServerLogger mockServerLogger, Http2Connection connection, Http2FrameListener frameListener, Http2FrameLogger frameLogger, boolean addedWhileReading) {
        return tunnelServerHandlerBuilder(configuration, mockServerLogger, connection, frameListener, frameLogger)
            .flushPreface(!addedWhileReading)
            .build();
    }

    static TunnelServerHandlerBuilder tunnelServerHandlerBuilder(Configuration configuration, MockServerLogger mockServerLogger, Http2Connection connection, Http2FrameListener frameListener, Http2FrameLogger frameLogger) {
        return new TunnelServerHandlerBuilder(mockServerLogger, serverSettings(configuration), connection, frameListener, frameLogger);
    }

    /**
     * Netty's own handling runs whatever the logging does: it is what answers 431, resets the stream or sends GOAWAY.
     */
    static void logRefusalThen(MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Http2Settings settings, boolean outbound, Throwable cause, Runnable nettysOnError) {
        try {
            logRefusal(mockServerLogger, ctx, settings, outbound, cause);
            Http2StreamFaults.logError(mockServerLogger, ctx, outbound, cause);
        } finally {
            nettysOnError.run();
        }
    }

    /**
     * Logs a connection error an {@code HttpToHttp2ConnectionHandler} raised as it read, as a direct connection logs
     * it: unlike {@code Http2FrameCodec}, that handler fires none down the pipeline, but answers it with a
     * {@code GOAWAY} and a close in Netty's own handling, which runs whatever the logging does.
     */
    static void logConnectionErrorThen(MockServerLogger mockServerLogger, ChannelHandlerContext ctx, boolean outbound, Throwable cause, Runnable nettysOnError) {
        try {
            if (!outbound && isConnectionError(cause)) {
                Http2ConnectionExceptionHandler.log(mockServerLogger, ctx, cause);
            }
        } finally {
            nettysOnError.run();
        }
    }

    /**
     * Whether a connection error is a write that failed because the connection's output has ended, which on an accepted
     * connection is then read to the end of its input ({@link ReadAfterFailedWrite}). Netty would answer it with a
     * {@code GOAWAY}, after which it ignores the streams the client opened later, and a close.
     */
    static boolean isWriteFailedAsTheOutputEnded(ChannelHandlerContext ctx, boolean outbound, Http2Exception http2Ex) {
        return outbound && http2Ex == null && ReadAfterFailedWrite.isReadingOn(ctx.channel());
    }

    /**
     * Marks {@code clientChannel}, the connection or a tunnel's client leg, closed by MockServer for a connection error
     * Netty's handler read, before it answers with a {@code GOAWAY} and a close: a tunnel's handler fires the error down
     * no pipeline, and a direct connection's codec closes before a close a handler queues for it runs. Not for an error
     * raised writing, nor a client already gone: its channel closed, or its output ended by a failed write.
     */
    static void markClosedForConnectionError(Channel clientChannel, boolean outbound) {
        if (!outbound && clientChannel.isActive() && !ReadAfterFailedWrite.isReadingOn(clientChannel)) {
            ServerClosedChannels.markClosed(clientChannel);
        }
    }

    /**
     * A close asked for while the connection is read to the end of its input waits for that end; through Netty's
     * handler it would send a {@code GOAWAY} first, as {@link #isWriteFailedAsTheOutputEnded} describes.
     */
    static void closeOnceReadUnlessReadingOn(ChannelHandlerContext ctx, ChannelPromise promise, CloseAction nettysClose) throws Exception {
        if (ReadAfterFailedWrite.isReadingOn(ctx.channel())) {
            ReadAfterFailedWrite.closeWhenInputEnds(ctx.channel(), promise);
        } else {
            nettysClose.close();
        }
    }

    interface CloseAction {
        void close() throws Exception;
    }

    static void logRefusal(MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Http2Settings settings, boolean outbound, Throwable cause) {
        if (outbound || mockServerLogger == null || !mockServerLogger.isEnabledForInstance(Level.WARN)) {
            return;
        }
        Http2Exception failure = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
        if (isHeaderListOverLimit(cause)) {
            int streamId = ((Http2Exception.HeaderListSizeException) failure).streamId();
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat(isKnownStream(ctx, streamId)
                        ? "resetting HTTP/2 stream:{}from:{}because the request's trailers are larger than maxHeaderSize:{}"
                        : "refusing request on HTTP/2 stream:{}from:{}because its header list is larger than maxHeaderSize:{}")
                    .setArguments(streamId, ctx.channel().remoteAddress(), settings.maxHeaderListSize())
            );
        } else if (isHeaderBlockTooLarge(failure)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("closing HTTP/2 connection from:{}because a request's header block is more than a quarter larger than maxHeaderSize:{}")
                    .setArguments(ctx.channel().remoteAddress(), settings.maxHeaderListSize())
            );
        }
    }

    /**
     * @return whether Netty refused a header list it read, a request's headers or its trailers, for its size
     */
    static boolean isHeaderListOverLimit(Throwable cause) {
        Http2Exception failure = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
        return failure instanceof Http2Exception.HeaderListSizeException && ((Http2Exception.HeaderListSizeException) failure).duringDecode();
    }

    static void trailersRefused(Channel stream) {
        stream.attr(TRAILERS_REFUSED).set(Boolean.TRUE);
    }

    /**
     * Whether {@code cause} is the aggregator reporting the part of a request it held when the stream was reset for
     * its trailers: expected, and already logged as the refusal, so not worth logging again.
     */
    public static boolean isRefusedRequestCutShort(Channel stream, Throwable cause) {
        return cause instanceof PrematureChannelClosureException && stream.hasAttr(TRAILERS_REFUSED);
    }

    /**
     * A request's headers open their stream only once they have been read whole, so a header list refused on a
     * stream the connection already knows is not the first on it: it is the request's trailers.
     */
    private static boolean isKnownStream(ChannelHandlerContext ctx, int streamId) {
        return ctx.handler() instanceof Http2ConnectionHandler && ((Http2ConnectionHandler) ctx.handler()).connection().streamMayHaveExisted(streamId);
    }

    /**
     * @return whether the failure is a request refused for its header size, which {@link #logRefusal} reports where
     * Netty raises it, so whatever sees the failure afterwards has nothing to add
     */
    static boolean isRefusal(Http2Exception failure) {
        return isHeaderListOverLimit(failure) || isHeaderBlockTooLarge(failure);
    }

    /**
     * @return whether Netty's {@code Http2ConnectionHandler.onError} handles {@code cause} as an error of the whole
     * connection, which it answers with a {@code GOAWAY} and a close
     */
    static boolean isConnectionError(Throwable cause) {
        Http2Exception failure = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
        return !Http2Exception.isStreamError(failure) && !(failure instanceof Http2Exception.CompositeStreamException);
    }

    private static boolean isHeaderBlockTooLarge(Http2Exception failure) {
        return failure != null
            && !Http2Exception.isStreamError(failure)
            && failure.error() == Http2Error.PROTOCOL_ERROR
            && failure.getMessage() != null
            && failure.getMessage().startsWith(HEADER_BLOCK_TOO_LARGE);
    }

    /**
     * Netty's {@code HttpToHttp2ConnectionHandlerBuilder}, which is final, with the handler's encoder wrapped.
     */
    static final class RelayLoopbackHandlerBuilder extends AbstractHttp2ConnectionHandlerBuilder<HttpToHttp2ConnectionHandler, RelayLoopbackHandlerBuilder> {

        private final MockServerLogger mockServerLogger;
        private final Channel proxyClient;

        private RelayLoopbackHandlerBuilder(MockServerLogger mockServerLogger, Channel proxyClient, Http2Connection connection, Http2FrameListener frameListener, Http2FrameLogger frameLogger) {
            this.mockServerLogger = mockServerLogger;
            this.proxyClient = proxyClient;
            initialSettings(relayLoopbackSettings());
            connection(connection);
            frameListener(frameListener);
            if (frameLogger != null) {
                frameLogger(frameLogger);
            }
        }

        @Override
        protected HttpToHttp2ConnectionHandler build() {
            return super.build();
        }

        @Override
        protected HttpToHttp2ConnectionHandler build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings) {
            return new HttpToHttp2ConnectionHandler(decoder, new ExtensionHeaderStrippingHttp2ConnectionEncoder(encoder), initialSettings, isValidateHeaders(), decoupleCloseAndGoAway(), flushPreface(), null) {
                @Override
                public void onError(ChannelHandlerContext ctx, boolean outbound, Throwable cause) {
                    logConnectionErrorThen(mockServerLogger, ctx, outbound, cause, () -> super.onError(ctx, outbound, cause));
                }

                @Override
                protected void onConnectionError(ChannelHandlerContext ctx, boolean outbound, Throwable cause, Http2Exception http2Ex) {
                    markClosedForConnectionError(proxyClient, outbound);
                    super.onConnectionError(ctx, outbound, cause, http2Ex);
                }
            };
        }
    }

    static final class TunnelServerHandlerBuilder extends AbstractHttp2ConnectionHandlerBuilder<HttpToHttp2ConnectionHandler, TunnelServerHandlerBuilder> {

        private final MockServerLogger mockServerLogger;

        private TunnelServerHandlerBuilder(MockServerLogger mockServerLogger, Http2Settings settings, Http2Connection connection, Http2FrameListener frameListener, Http2FrameLogger frameLogger) {
            this.mockServerLogger = mockServerLogger;
            initialSettings(settings);
            connection(connection);
            frameListener(frameListener);
            if (frameLogger != null) {
                frameLogger(frameLogger);
            }
        }

        @Override
        protected HttpToHttp2ConnectionHandler build() {
            return super.build();
        }

        @Override
        protected TunnelServerHandlerBuilder flushPreface(boolean flushPreface) {
            return super.flushPreface(flushPreface);
        }

        @Override
        protected HttpToHttp2ConnectionHandler build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings) {
            // set here, which the superclass then leaves alone, so that the builder's own fields stay as Netty's builder has them
            decoder.frameListener(Http2StreamFaults.tunnelFrameListener(mockServerLogger, encoder.connection(), frameListener()));
            // a response read from the loopback carries the x-http2- headers its adapter set, which are not sent to the client
            return new HttpToHttp2ConnectionHandler(decoder, new ExtensionHeaderStrippingHttp2ConnectionEncoder(encoder), initialSettings, isValidateHeaders(), decoupleCloseAndGoAway(), flushPreface(), null) {
                @Override
                public void onError(ChannelHandlerContext ctx, boolean outbound, Throwable cause) {
                    logRefusalThen(mockServerLogger, ctx, initialSettings, outbound, cause,
                        () -> logConnectionErrorThen(mockServerLogger, ctx, outbound, cause, () -> super.onError(ctx, outbound, cause)));
                }

                @Override
                protected void onConnectionError(ChannelHandlerContext ctx, boolean outbound, Throwable cause, Http2Exception http2Ex) {
                    if (!isWriteFailedAsTheOutputEnded(ctx, outbound, http2Ex)) {
                        markClosedForConnectionError(ctx.channel(), outbound);
                        super.onConnectionError(ctx, outbound, cause, http2Ex);
                    }
                }

                @Override
                public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
                    closeOnceReadUnlessReadingOn(ctx, promise, () -> super.close(ctx, promise));
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                    Http2StreamFaults.logRequestsEndedWithConnection(mockServerLogger, ctx, connection());
                    super.channelInactive(ctx);
                }
            };
        }
    }
}

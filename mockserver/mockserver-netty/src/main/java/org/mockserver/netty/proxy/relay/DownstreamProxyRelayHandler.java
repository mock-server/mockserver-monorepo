package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.log.model.LogEntry;
import org.mockserver.log.model.SensitiveLogValue;
import org.mockserver.mappers.NettyMessageForLog;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.responsewriter.ResponseWrittenBeneathCodecEvent;
import org.mockserver.socket.ChannelReadPause;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.ReadAfterFailedWrite;
import org.slf4j.event.Level;

import java.util.concurrent.atomic.AtomicLong;

import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;
import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.closeOnFlush;
import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;
import static org.mockserver.exception.ExceptionHandling.socketClosedException;

public class DownstreamProxyRelayHandler extends SimpleChannelInboundHandler<HttpObject> {

    private final MockServerLogger mockServerLogger;
    private final Channel upstreamChannel;
    private final boolean bounded;
    private final long maxUnwrittenStreamedBytes;
    private final long pauseReadsAboveBytes;
    private final AtomicLong unwrittenStreamedBytes = new AtomicLong();
    // counted apart so a raw backlog pauses reads but never aborts a streamed response behind it
    private final AtomicLong unwrittenRawBytes = new AtomicLong();
    // both confined to the loopback's event loop, which the proxy client's channel shares
    private boolean relayEnded;
    private boolean readsPaused;
    // the tunnel carries the protocol a 101 switched to; its bytes go by TunnelBinaryPassthrough
    private boolean upgraded;

    /**
     * Above this many unwritten streamed and raw bytes together (or half the bound, if less) the loopback stops reading
     * until they drain to half as many, so a slow proxy client gets backpressure long before
     * {@code maxUnwrittenStreamedBytes} aborts.
     */
    static final long PAUSE_READS_ABOVE_BYTES = 256 * 1024;

    /**
     * Relays with no bound on the streamed bytes not yet written to {@code upstreamChannel}.
     */
    public DownstreamProxyRelayHandler(MockServerLogger mockServerLogger, Channel upstreamChannel) {
        this(mockServerLogger, upstreamChannel, false, 0);
    }

    /**
     * @param maxUnwrittenStreamedBytes the most bytes of streamed (unaggregated) content relayed but not yet written to
     *                                  {@code upstreamChannel}, past which both channels are closed, normally
     *                                  {@code maxRequestBodySize}; at zero or less the first streamed byte closes them.
     *                                  One read of a compressed response can decode to far more than any heap.
     */
    public DownstreamProxyRelayHandler(MockServerLogger mockServerLogger, Channel upstreamChannel, long maxUnwrittenStreamedBytes) {
        this(mockServerLogger, upstreamChannel, true, Math.max(0, maxUnwrittenStreamedBytes));
    }

    private DownstreamProxyRelayHandler(MockServerLogger mockServerLogger, Channel upstreamChannel, boolean bounded, long maxUnwrittenStreamedBytes) {
        super(false);
        this.upstreamChannel = upstreamChannel;
        this.mockServerLogger = mockServerLogger;
        this.bounded = bounded;
        this.maxUnwrittenStreamedBytes = maxUnwrittenStreamedBytes;
        this.pauseReadsAboveBytes = Math.min(PAUSE_READS_ABOVE_BYTES, maxUnwrittenStreamedBytes / 2);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
        ctx.write(Unpooled.EMPTY_BUFFER);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof RawResponseBytes) {
            relay(ctx, msg);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) {
        relay(ctx, msg);
    }

    private void relay(final ChannelHandlerContext ctx, final Object msg) {
        if (relayEnded) {
            // the rest of the read that ended the relay is still decoded, and dropped here
            ReferenceCountUtil.release(msg);
            return;
        }
        if (upgraded && msg instanceof HttpContent) {
            // the empty end of a 101 the aggregator did not join to it
            ReferenceCountUtil.release(msg);
            return;
        }
        final boolean rawBytes = msg instanceof RawResponseBytes;
        // an aggregated message is already bounded by its aggregator
        final int streamedBytes = rawBytes ? ((RawResponseBytes) msg).content().readableBytes()
            : msg instanceof HttpContent && !(msg instanceof FullHttpMessage) ? ((HttpContent) msg).content().readableBytes() : 0;
        final AtomicLong itsCount = rawBytes ? unwrittenRawBytes : unwrittenStreamedBytes;
        final boolean counted = streamedBytes > 0 && bounded;
        final long unwrittenOfItsKind = counted ? itsCount.addAndGet(streamedBytes) : 0;
        // raw bytes are never cut short, as on a direct connection: the pause below is what bounds them
        if (unwrittenOfItsKind > maxUnwrittenStreamedBytes && !rawBytes) {
            ReferenceCountUtil.release(msg);
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("aborting streamed response relayed to {} because more than {} bytes of it were waiting to be written to the client")
                        .setArguments(upstreamChannel.remoteAddress(), maxUnwrittenStreamedBytes)
                );
            }
            // no terminating chunk is written, so the client sees an incomplete response
            RelayLegClose.markClientLegClosedByServer(upstreamChannel);
            endRelay(ctx);
            return;
        }
        if (counted && unwrittenBytes() > pauseReadsAboveBytes && !readsPaused) {
            readsPaused = true;
            ChannelReadPause.pause(ctx.channel());
        }
        // read before the write, which releases the message
        final Integer clientStreamId = msg instanceof StreamedHttp2ResponsePart ? Integer.valueOf(((StreamedHttp2ResponsePart) msg).streamId())
            : msg instanceof HttpMessage ? ((HttpMessage) msg).headers().getInt(STREAM_ID.text()) : null;
        final boolean upgrade = !rawBytes && TunnelUpgrade.isUpgrade(msg) && upstreamChannel.pipeline().get(HttpServerCodec.class) != null;
        final ChannelFuture written = rawBytes ? writeBeneathCodec((RawResponseBytes) msg) : upstreamChannel.writeAndFlush(msg);
        if (upgrade && upstreamChannel.isActive()) {
            // the 101 has passed the client leg's codec; what follows on both legs is the new protocol's bytes
            upgraded = true;
            TunnelUpgrade.switchToBytes(upstreamChannel, ctx.channel(), mockServerLogger);
        }
        written.addListener((ChannelFutureListener) future -> {
            if (counted) {
                itsCount.addAndGet(-streamedBytes);
                if (readsPaused && unwrittenBytes() <= pauseReadsAboveBytes / 2) {
                    readsPaused = false;
                    ChannelReadPause.resume(ctx.channel());
                }
            }
            if (future.isSuccess()) {
                // while paused, reads resume only when the backlog has drained (resume above)
                if (!readsPaused && !relayEnded) {
                    ctx.read();
                }
            } else if (clientStreamId != null && isStreamFailure(future.cause()) && upstreamChannel.isActive()) {
                // only that HTTP/2 stream is gone (reset by its client or by the stream write-stall watcher), so the
                // tunnel carries on with the others
                if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.DEBUG)
                            .setMessageFormat("response on stream {} to {} was not relayed because the stream had closed: {}")
                            .setArguments(clientStreamId, upstreamChannel.remoteAddress(), future.cause().getMessage())
                    );
                }
                if (!readsPaused && !relayEnded) {
                    ctx.read();
                }
            } else if (!relayEnded) {
                // every write already queued behind this one fails the same way, so only the first is logged; a client
                // connection that has closed is not a failure worth an error (its streams' writes fail with it)
                if (upstreamChannel.isActive() && !socketClosedException(future.cause())) {
                    mockServerLogger.logEvent(writeFailure(msg, future.cause()));
                }
                endRelay(ctx);
            }
        });
    }

    private long unwrittenBytes() {
        return unwrittenStreamedBytes.get() + unwrittenRawBytes.get();
    }

    /**
     * Writes raw bytes from the context of the client leg's codec, as MockServer wrote them from its own. With the last
     * of them it tells that codec, which pairs each request with the next response it encodes, and ends the client
     * leg's exchange as they are written: no response passes that leg's codec to do either.
     */
    private ChannelFuture writeBeneathCodec(RawResponseBytes rawBytes) {
        ChannelHandlerContext codec = upstreamChannel.pipeline().context(HttpServerCodec.class);
        if (codec == null) {
            // a closed channel's pipeline is empty: the write fails as any other to it does
            return upstreamChannel.writeAndFlush(rawBytes.content());
        }
        ChannelFuture written = codec.writeAndFlush(rawBytes.content());
        if (rawBytes.endsResponse()) {
            codec.fireUserEventTriggered(ResponseWrittenBeneathCodecEvent.INSTANCE);
            written.addListener(future -> codec.fireUserEventTriggered(HttpExchangeEndedEvent.RAW_RESPONSE_WRITTEN));
        }
        return written;
    }

    /**
     * Closes both legs and stops reading the loopback. Closing the proxy client's channel is not enough: one that stays
     * open while refusing writes (a TLS engine closed with its {@code close_notify} queued behind unread bytes) never
     * fires the {@code channelInactive} that closes the loopback, which would keep reading and relaying into it.
     * The loopback's socket is closed directly ({@link RelayLegClose}): its streams can no longer complete, and an
     * HTTP/2 loopback closed through its pipeline would wait for them.
     * <p>
     * The loopback of a proxy client that has gone, or whose output a failed write has ended (it is read to the end of
     * its input, {@link ReadAfterFailedWrite}), is left to {@link UpstreamProxyRelayHandler}, which ends it when that
     * client's leg closes, and is read (and dropped) meanwhile. A request written to the loopback may not yet have been
     * read by MockServer, and closing the loopback's socket with responses unread resets it, which drops that request.
     */
    private void endRelay(ChannelHandlerContext ctx) {
        relayEnded = true;
        if (!upstreamChannel.isActive() || ReadAfterFailedWrite.isReadingOn(upstreamChannel)) {
            return;
        }
        // never released: it also stops a read in progress from going on to read the socket closed below
        ChannelReadPause.pause(ctx.channel());
        upstreamChannel.close();
        RelayLegClose.now(ctx.channel());
    }

    /**
     * The relayed message's text lists every header, so it is logged with its headers attached for redactSecretsInLog
     * to mask; a message with no headers of its own is masked whole. The text is taken now: the message may already
     * be released, and the entry must not keep it.
     */
    static LogEntry writeFailure(Object msg, Throwable cause) {
        LogEntry logEntry = new LogEntry()
            .setLogLevel(Level.ERROR)
            .setMessageFormat("exception while returning writing:{}")
            .setThrowable(boundedFault(cause));
        String text = String.valueOf(msg);
        org.mockserver.model.HttpResponse headersOfResponse = msg instanceof io.netty.handler.codec.http.HttpResponse ? NettyMessageForLog.response((io.netty.handler.codec.http.HttpResponse) msg) : null;
        org.mockserver.model.HttpRequest headersOfRequest = msg instanceof io.netty.handler.codec.http.HttpRequest ? NettyMessageForLog.request((io.netty.handler.codec.http.HttpRequest) msg) : null;
        if (headersOfResponse != null) {
            logEntry.setHttpResponse(headersOfResponse).setArguments(text);
        } else if (headersOfRequest != null) {
            logEntry.setHttpRequest(headersOfRequest).setArguments(text);
        } else {
            // no headers to redact against: masked whole
            logEntry.setArguments(SensitiveLogValue.of(text));
        }
        return logEntry;
    }

    private static boolean isStreamFailure(Throwable cause) {
        return Http2CodecUtil.getEmbeddedHttp2Exception(cause) instanceof Http2Exception.StreamException;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closeOnFlush(upstreamChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (directMemoryLimitReached(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + cause.getMessage())
            );
            // the loopback's close closes the client leg, whose mark the loopback's far end consults
            RelayLegClose.markClientLegClosedByServer(upstreamChannel);
        } else if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by downstream relay handler -> closing pipeline " + ctx.channel())
                    .setThrowable(cause)
            );
            RelayLegClose.markClientLegClosedByServer(upstreamChannel);
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("SSL or decoder fault caught by downstream relay handler -> closing pipeline " + ctx.channel() + sniDescription(ctx.channel(), upstreamChannel))
                        .setThrowable(boundedFault(cause))
                );
            }
            RelayLegClose.markClientLegClosedByServer(upstreamChannel);
        }
        closeOnFlush(ctx.channel());
    }

}

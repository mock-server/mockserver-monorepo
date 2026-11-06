package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.ssl.SslHandler;
import org.mockserver.codec.HttpChunkLineLimiter;
import org.mockserver.codec.StreamingAwareHttpObjectAggregator;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.unification.Http1RequestCutShort;
import org.slf4j.event.Level;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;

import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.closeOnFlush;
import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;
import static org.mockserver.model.Protocol.HTTP_2;
import static org.mockserver.netty.unification.PortUnificationHandler.isSslEnabledDownstream;
import static org.mockserver.netty.unification.PortUnificationHandler.nettySslContextFactory;
import static org.mockserver.socket.tls.SniHandler.getALPNProtocol;

public class UpstreamProxyRelayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final MockServerLogger mockServerLogger;
    private final Channel upstreamChannel;
    private final Channel downstreamChannel;
    private final String host;
    private final int port;
    private final int maxRequestBodySize;
    // both confined to the proxy client's event loop, which the loopback shares
    private int requestWritesInProgress;
    private boolean clientGone;

    /**
     * @param maxRequestBodySize the most decoded bytes of a compressed request body scanned for a streaming request
     */
    public UpstreamProxyRelayHandler(MockServerLogger mockServerLogger, Channel upstreamChannel, Channel downstreamChannel, String host, int port, int maxRequestBodySize) {
        super(false);
        this.maxRequestBodySize = maxRequestBodySize;
        this.upstreamChannel = upstreamChannel;
        this.downstreamChannel = downstreamChannel;
        this.host = host;
        this.port = port;
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
        ctx.write(Unpooled.EMPTY_BUFFER);
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest request) {
        if (isSslEnabledDownstream(upstreamChannel) && downstreamChannel.pipeline().get(SslHandler.class) == null) {
            io.netty.handler.ssl.SslContext clientSslContext = nettySslContextFactory(ctx.channel())
                .createClientSslContext(true, HTTP_2.equals(getALPNProtocol(mockServerLogger, ctx)));
            // Give HTTPS endpoint identification (host name verification for JVM / CUSTOM trust managers) a
            // reference identity: the CONNECT target host/port, mirroring the sibling loopback TLS in
            // RelayConnectHandler#configurePipelines. The connected downstream SOCKET address is the wrong
            // thing to verify against — its getHostString() is the MockServer loopback ("0.0.0.0") on the
            // common forward-proxy path and only coincidentally the target on a reverse-proxy path — so the
            // identity is keyed off the CONNECT host, never the socket. Falls back to the no-host handler
            // (verification skipped, as before) only when the CONNECT host is unknown.
            SslHandler sslHandler = isNotBlank(host)
                ? clientSslContext.newHandler(ctx.alloc(), host, port)
                : clientSslContext.newHandler(ctx.alloc());
            downstreamChannel.pipeline().addFirst(sslHandler);
        }
        // Propagate the request's streaming intent onto the loopback channel so the relay-only
        // StreamingAwareHttpObjectAggregator streams the matching response incrementally even when the
        // upstream omits Content-Type: text/event-stream (e.g. the OpenAI Codex backend used by the
        // opencode CLI). Set per-request (overwritten on every request, true OR cleared to null) so a
        // keep-alive tunnel carrying many requests applies the intent only to the response it belongs to.
        downstreamChannel.attr(StreamingAwareHttpObjectAggregator.EXPECT_STREAMING_RESPONSE)
            .set(StreamingAwareHttpObjectAggregator.requestExpectsStreamingResponse(request, maxRequestBodySize) ? Boolean.TRUE : null);
        // Diagnostic only — record the forward time and request line so the relay-only
        // StreamingAwareHttpObjectAggregator can report a time-to-first-byte in its DEBUG
        // streaming-decision log when the matching response head arrives. Behaviour-preserving.
        downstreamChannel.attr(StreamingAwareHttpObjectAggregator.REQUEST_FORWARDED_NANOS).set(System.nanoTime());
        // raw path, query string dropped: this line is logged as plain text, where a query-string credential could
        // not be masked. Never decode here: a malformed escape would throw before the request is relayed or released.
        downstreamChannel.attr(StreamingAwareHttpObjectAggregator.REQUEST_LINE).set(request.method() + " " + org.apache.commons.lang3.StringUtils.substringBefore(request.uri(), "?"));
        // read before the write, which may change it on the loopback
        final Integer clientStreamId = request.headers().getInt(STREAM_ID.text());
        requestWritesInProgress++;
        downstreamChannel.writeAndFlush(request).addListener((ChannelFutureListener) future -> {
            requestWritesInProgress--;
            if (clientGone && requestWritesInProgress == 0) {
                RelayLegClose.afterFlush(downstreamChannel);
            }
            if (future.isSuccess()) {
                ctx.channel().read();
            } else if (clientStreamId != null && future.channel().isActive() && resetFailedStream(ctx, clientStreamId, future.cause())) {
                // only this stream failed, so the loopback carries on with the others
                ctx.read();
            } else {
                if (isNotSocketClosedException(future.cause())) {
                    org.mockserver.model.HttpRequest loggedRequest = loggedRequest(request);
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setHttpRequest(loggedRequest)
                            .setMessageFormat("exception while returning response for request:{}")
                            .setArguments(loggedRequest)
                            .setThrowable(boundedFault(future.cause()))
                    );
                }
                future.channel().close();
            }
        });
    }

    /**
     * Resets the proxy client's HTTP/2 stream when its request could not be written to the loopback because of that
     * stream alone, returning false when the failure is not one stream's (or the tunnel is HTTP/1.1). Netty refuses to
     * open a loopback stream above a received GOAWAY's last stream id, or past MockServer's concurrent-stream limit,
     * with {@code REFUSED_STREAM}: MockServer never saw the request, so the client may retry it (RFC 9113 section 8.7).
     * The reset waits for the event loop's next task: a write can fail while the client's own RST_STREAM for the stream
     * is being read, and the stream is gone only after that, so it is not then answered with a reset of its own.
     */
    private boolean resetFailedStream(ChannelHandlerContext ctx, int clientStreamId, Throwable cause) {
        Http2Exception failure = Http2CodecUtil.getEmbeddedHttp2Exception(cause);
        ChannelHandlerContext clientCtx = ctx.pipeline().context(Http2ConnectionHandler.class);
        if (!(failure instanceof Http2Exception.StreamException) || clientCtx == null) {
            return false;
        }
        long errorCode = (failure.error() == Http2Error.REFUSED_STREAM ? Http2Error.REFUSED_STREAM : Http2Error.INTERNAL_ERROR).code();
        clientCtx.executor().execute(() -> {
            Http2ConnectionHandler clientHandler = (Http2ConnectionHandler) clientCtx.handler();
            Http2Stream clientStream = clientHandler.connection().stream(clientStreamId);
            // a closed client connection has already closed every stream
            if (clientStream == null || clientStream.isResetSent()) {
                return;
            }
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("request on stream {} from {} could not be relayed, resetting the client's stream with error code {}")
                        .setArguments(clientStreamId, clientCtx.channel().remoteAddress(), errorCode)
                        .setThrowable(cause)
                );
            }
            clientHandler.resetStream(clientCtx, clientStreamId, errorCode, clientCtx.newPromise());
            clientCtx.flush();
        });
        return true;
    }

    private boolean isNotSocketClosedException(Throwable cause) {
        return !(cause instanceof ClosedChannelException || cause instanceof ClosedSelectorException);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        clientGone = true;
        if (requestWritesInProgress == 0) {
            // no response the loopback still has in flight can be delivered
            RelayLegClose.afterFlush(downstreamChannel);
        } else if (downstreamChannel.pipeline().get(Http2ConnectionHandler.class) != null) {
            // the last request write's listener ends the loopback; this graceful close bounds the wait for HTTP/2 DATA
            // held for flow-control window, which a socket close would fail
            closeOnFlush(downstreamChannel);
        }
        // an HTTP/1.1 loopback is left to that listener: closed as soon as the request is flushed, with a response
        // unread, it would be reset before MockServer had read the request
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (HttpChunkLineLimiter.isRejectedRequestCutShort(ctx.channel(), cause)) {
            // already logged as the rejection that closed the connection
        } else if (Http1RequestCutShort.isRequestCutShort(mockServerLogger, ctx, cause)) {
            // a request cut short by the client leg's end: logged as that
        } else if (directMemoryLimitReached(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + cause.getMessage())
            );
            RelayLegClose.markClientLegClosedByServer(ctx.channel());
        } else if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by upstream relay handler -> closing pipeline " + ctx.channel())
                    .setThrowable(cause)
            );
            RelayLegClose.markClientLegClosedByServer(ctx.channel());
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("SSL or decoder fault caught by upstream relay handler -> closing pipeline " + ctx.channel() + sniDescription(ctx.channel(), upstreamChannel, downstreamChannel))
                        .setThrowable(boundedFault(cause))
                );
            }
            RelayLegClose.markClientLegClosedByServer(ctx.channel());
        }
        closeOnFlush(ctx.channel());
    }

    /**
     * The relayed request's method, path and query string as a request, so a credential in the query string is
     * masked like any other logged request's when redactSecretsInLog is on.
     */
    static org.mockserver.model.HttpRequest loggedRequest(FullHttpRequest request) {
        String uri = request.uri();
        org.mockserver.model.HttpRequest loggedRequest = org.mockserver.model.HttpRequest.request(org.apache.commons.lang3.StringUtils.substringBefore(uri, "?")).withMethod(request.method().name());
        try {
            new io.netty.handler.codec.http.QueryStringDecoder(uri).parameters()
                .forEach((name, values) -> loggedRequest.withQueryStringParameter(name, values.toArray(new String[0])));
        } catch (RuntimeException undecodableQueryString) {
            // left out rather than logged raw: it may carry a credential, and this runs on the failure path
        }
        return loggedRequest;
    }
}

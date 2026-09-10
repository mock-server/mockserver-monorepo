package org.mockserver.netty.responsewriter;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.Http2StreamIds;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.action.http.TcpChaosRegistry;
import org.mockserver.mock.breakpoint.PausedStreamFrame;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.mock.breakpoint.StreamFrameDecision;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;
import org.mockserver.model.TcpChaosProfile;
import org.mockserver.netty.unification.Http2GoAwayEmitter;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

/**
 * @author jamesdbloom
 */
public class NettyResponseWriter extends ResponseWriter {

    private final ChannelHandlerContext ctx;
    private final Scheduler scheduler;
    // Request-received time for the latency histogram; -1 when metrics are
    // disabled so sendResponse() adds nothing to the hot path. A new
    // NettyResponseWriter is created per request, so this is race-free.
    private final long startNanos;
    // WS7.2 graceful-shutdown in-flight token for this exchange; may be null (e.g. when no
    // LifeCycle is available). Completed exactly once when the response is dispatched here,
    // releasing this request from the drain counter. The token's own guard makes this safe even
    // when the channel-close safety net also fires.
    private final org.mockserver.netty.InFlightRequest inFlightRequest;

    public NettyResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Scheduler scheduler) {
        this(configuration, mockServerLogger, ctx, scheduler, null);
    }

    public NettyResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Scheduler scheduler, org.mockserver.netty.InFlightRequest inFlightRequest) {
        super(configuration, mockServerLogger);
        this.ctx = ctx;
        this.scheduler = scheduler;
        this.startNanos = configuration.metricsEnabled() ? System.nanoTime() : -1L;
        this.inFlightRequest = inFlightRequest;
    }

    @Override
    public void sendResponse(HttpRequest request, HttpResponse response) {
        if (startNanos >= 0) {
            double durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            Metrics.observeRequestDurationSeconds(durationSeconds);
            Metrics.observeRequestDurationByMethodSeconds(durationSeconds, request != null ? request.getMethod("") : null);
        }
        // Release this exchange from the graceful-shutdown drain counter now that its response is
        // being dispatched. This is the single funnel for every data-plane response (normal,
        // streaming, chunked, forward/proxy, error, breakpoint-modified), so it covers all of them
        // with one decrement. The token guards against double-completion if the channel-close net
        // also fires. Done here (response hand-off) rather than per-write-future to keep it off the
        // many divergent async write sub-paths; the close-future net backstops dropped exchanges.
        if (inFlightRequest != null) {
            inFlightRequest.complete();
        }
        if (response.getStreamingBody() != null) {
            writeStreamingResponse(ctx, request, response);
        } else {
            writeAndCloseSocket(ctx, request, response);
        }
    }

    /**
     * Build the terminating {@link LastHttpContent} for a streaming response. When the response
     * carries trailers, return a fresh {@link DefaultLastHttpContent} whose trailing headers carry
     * them (mirroring the {@code MockServerHttpResponseToFullHttpResponse} mapper); otherwise reuse
     * the shared {@link LastHttpContent#EMPTY_LAST_CONTENT} singleton (which must never be mutated).
     */
    private static LastHttpContent lastContentWithTrailers(HttpResponse response) {
        if (response.getTrailerMultimap() == null || response.getTrailerMultimap().isEmpty()) {
            return LastHttpContent.EMPTY_LAST_CONTENT;
        }
        DefaultLastHttpContent lastContent = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER);
        response.getTrailerMultimap().entries().forEach(entry ->
            lastContent.trailingHeaders().add(
                sanitizeHeaderValue(entry.getKey().getValue()),
                sanitizeHeaderValue(entry.getValue().getValue())
            )
        );
        return lastContent;
    }

    private static String sanitizeHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r", "").replace("\n", "");
    }

    private void writeStreamingResponse(ChannelHandlerContext ctx, HttpRequest request, HttpResponse response) {
        StreamingBody streamingBody = response.getStreamingBody();

        // Build a Netty DefaultHttpResponse head (not Full)
        int statusCode = response.getStatusCode() != null ? response.getStatusCode() : 200;
        HttpResponseStatus status;
        if (response.getReasonPhrase() != null && !response.getReasonPhrase().isEmpty()) {
            status = new HttpResponseStatus(statusCode, response.getReasonPhrase());
        } else {
            status = HttpResponseStatus.valueOf(statusCode);
        }
        DefaultHttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);

        // Copy headers from the MockServer response
        if (response.getHeaderMultimap() != null) {
            response.getHeaderMultimap().entries().forEach(entry ->
                nettyResponse.headers().add(entry.getKey().getValue(), entry.getValue().getValue())
            );
        }

        // The stream id lives in a protocol-guarded field, NOT in the header multimap copied above,
        // so copying headers alone drops it - and an HTTP/2 client then receives nothing for the
        // whole streaming response. ResponseWriter.writeResponse has already populated the field
        // from the request; this is the only place that turns it back into the wire header on this
        // path, because a streaming response never reaches the response mapper (see Http2StreamIds).
        Http2StreamIds.stamp(nettyResponse, response.getStreamId());

        // Ensure chunked transfer encoding
        if (!nettyResponse.headers().contains(HttpHeaderNames.TRANSFER_ENCODING)) {
            HttpUtil.setTransferEncodingChunked(nettyResponse, true);
        }

        // When the streaming response carries trailers, announce them via a Trailer header on
        // the head (RFC 9110 section 6.5.1). The trailing-header block itself is written on the
        // final LastHttpContent at stream completion (see onComplete below). The stream is
        // already chunked, which is the framing trailers require on HTTP/1.1.
        final boolean hasTrailers = response.getTrailerMultimap() != null && !response.getTrailerMultimap().isEmpty();
        if (hasTrailers && !nettyResponse.headers().contains(HttpHeaderNames.TRAILER)) {
            java.util.LinkedHashSet<String> trailerNames = new java.util.LinkedHashSet<>();
            response.getTrailerMultimap().keySet().forEach(name -> trailerNames.add(sanitizeHeaderValue(name.getValue())));
            if (!trailerNames.isEmpty()) {
                nettyResponse.headers().set(HttpHeaderNames.TRAILER, String.join(", ", trailerNames));
            }
        }

        // Send the response head
        ctx.writeAndFlush(nettyResponse);

        // Determine if stream-frame breakpoints are active for this response
        final org.mockserver.mock.breakpoint.BreakpointMatcher streamBreakpointMatcher = org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().findMatch(request, org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM);
        final boolean streamBreakpointsActive = streamBreakpointMatcher != null;
        // Stream identifier and request metadata are only needed when breakpoints
        // are active — keep them out of the default-off hot path (zero allocation).
        final String streamId;
        final String reqMethod;
        final String reqPath;
        // WS-callback dispatch: when the matched breakpoint has a non-null clientId
        // AND the per-server WS registry is available on the channel, dispatch over WS
        final boolean useWsDispatch;
        final String breakpointClientId;
        final org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry wsRegistry;
        if (streamBreakpointsActive) {
            streamId = request.getLogCorrelationId() != null
                ? request.getLogCorrelationId() + "-stream"
                : java.util.UUID.randomUUID() + "-stream";
            reqMethod = request.getMethod() != null ? request.getMethod().getValue() : null;
            reqPath = request.getPath() != null ? request.getPath().getValue() : null;
            breakpointClientId = streamBreakpointMatcher.getClientId();
            wsRegistry = ctx.channel().attr(org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry.WS_REGISTRY_KEY).get();
            useWsDispatch = breakpointClientId != null && wsRegistry != null;
        } else {
            streamId = null;
            reqMethod = null;
            reqPath = null;
            useWsDispatch = false;
            breakpointClientId = null;
            wsRegistry = null;
        }

        // Subscribe to the streaming body to forward chunks as they arrive.
        // After each chunk write completes, call streamingBody.requestMore() to trigger
        // the next upstream read — this implements backpressure so a slow client does not
        // cause unbounded buffering on the server channel.
        streamingBody.subscribe(
            // onChunk
            chunk -> {
                if (!ctx.channel().isActive()) {
                    // Channel is no longer active; still request more so the upstream can
                    // detect the closed channel on the next read and clean up.
                    streamingBody.requestMore();
                    return;
                }

                if (!streamBreakpointsActive) {
                    // Default-off fast path: write the frame immediately (no interception)
                    DefaultHttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer(chunk));
                    ctx.writeAndFlush(content).addListener(future -> streamingBody.requestMore());
                    return;
                }

                // --- Stream-frame breakpoint path ---
                // Copy the chunk bytes for the registry (the ByteBuf is owned by the caller)
                byte[] chunkBytes = new byte[chunk.readableBytes()];
                chunk.getBytes(chunk.readerIndex(), chunkBytes);

                // WS-callback dispatch (clientId is always present — required since 7b)
                final java.util.concurrent.CompletableFuture<StreamFrameDecision> decisionFuture;
                int seq = StreamFrameBreakpointRegistry.getInstance()
                    .nextSequenceNumber(streamId);
                java.util.concurrent.CompletableFuture<StreamFrameDecision> wsFuture =
                    org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().dispatchFrame(
                        breakpointClientId, streamBreakpointMatcher.getId(), streamId, seq,
                        PausedStreamFrame.Direction.OUTBOUND,
                        org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM,
                        chunkBytes, reqMethod, reqPath,
                        wsRegistry,
                        configuration, mockServerLogger
                    );
                if (wsFuture == null) {
                    // Cap reached or client not connected — write immediately
                    DefaultHttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer(chunk));
                    ctx.writeAndFlush(content).addListener(future -> streamingBody.requestMore());
                    return;
                }
                decisionFuture = wsFuture;

                // Frame is parked (either in registry or dispatched via WS). The original
                // chunk ByteBuf is NOT retained — we copied the bytes above. The chunk will
                // be released by StreamingBody after onChunk returns.
                // We do NOT call streamingBody.requestMore() — this stops the upstream from
                // sending more chunks (backpressure). We will call it after the frame is resolved.

                // When the decision future completes (from control-plane API, WS reply, or timeout),
                // execute the action on the channel's event loop to ensure thread safety.
                decisionFuture.thenAccept(decision -> {
                    // Marshal onto the channel's event loop
                    ctx.channel().eventLoop().execute(() -> {
                        if (!ctx.channel().isActive()) {
                            streamingBody.requestMore();
                            return;
                        }
                        switch (decision.getAction()) {
                            case CONTINUE: {
                                DefaultHttpContent content = new DefaultHttpContent(
                                    Unpooled.wrappedBuffer(chunkBytes));
                                ctx.writeAndFlush(content).addListener(future -> streamingBody.requestMore());
                                break;
                            }
                            case MODIFY: {
                                DefaultHttpContent content = new DefaultHttpContent(
                                    Unpooled.wrappedBuffer(decision.getReplacementBody()));
                                ctx.writeAndFlush(content).addListener(future -> streamingBody.requestMore());
                                break;
                            }
                            case DROP: {
                                // Discard the frame — do not write anything to the client
                                streamingBody.requestMore();
                                break;
                            }
                            case INJECT: {
                                // Write the original frame, then inject an additional frame
                                DefaultHttpContent originalContent = new DefaultHttpContent(
                                    Unpooled.wrappedBuffer(chunkBytes));
                                ctx.writeAndFlush(originalContent).addListener(future -> {
                                    if (ctx.channel().isActive()) {
                                        DefaultHttpContent injectedContent = new DefaultHttpContent(
                                            Unpooled.wrappedBuffer(decision.getInjectedBody()));
                                        ctx.writeAndFlush(injectedContent).addListener(f2 -> streamingBody.requestMore());
                                    } else {
                                        streamingBody.requestMore();
                                    }
                                });
                                break;
                            }
                            case CLOSE: {
                                // End the stream: send LastHttpContent and close
                                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> {
                                    ctx.close();
                                    // Do NOT request more — stream is ended
                                });
                                break;
                            }
                            default: {
                                // Unrecognised action — log a warning and request more to avoid
                                // hanging the stream if a future action type is added without
                                // updating this switch.
                                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                                    mockServerLogger.logEvent(new LogEntry()
                                        .setLogLevel(WARN)
                                        .setMessageFormat("unrecognised stream frame breakpoint action: " + decision.getAction())
                                    );
                                }
                                streamingBody.requestMore();
                                break;
                            }
                        }
                    });
                });
            },
            // onComplete
            () -> {
                if (streamBreakpointsActive) {
                    // Evict any remaining held frames for this stream (prevents leaks)
                    StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                }
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(lastContentWithTrailers(response)).addListener(future -> {
                        boolean closeChannel;
                        ConnectionOptions connectionOptions = response.getConnectionOptions();
                        if (connectionOptions != null && connectionOptions.getCloseSocket() != null) {
                            closeChannel = connectionOptions.getCloseSocket();
                        } else {
                            closeChannel = !(request.isKeepAlive() != null && request.isKeepAlive());
                        }
                        if (closeChannel || configuration.alwaysCloseSocketConnections()) {
                            ctx.close();
                        }
                    });
                }
            },
            // onError
            error -> {
                if (streamBreakpointsActive) {
                    // Evict any remaining held frames for this stream (prevents leaks)
                    StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                }
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> ctx.close());
                }
            }
        );
    }

    private void writeAndCloseSocket(final ChannelHandlerContext ctx, final HttpRequest request, HttpResponse response) {
        boolean closeChannel;

        ConnectionOptions connectionOptions = response.getConnectionOptions();
        if (connectionOptions != null && connectionOptions.getCloseSocket() != null) {
            closeChannel = connectionOptions.getCloseSocket();
        } else {
            closeChannel = !(request.isKeepAlive() != null && request.isKeepAlive());
        }

        // Connection-lifecycle response-path faults (mid-response RST, HTTP/2 GOAWAY). Resolved from a
        // host-scoped TcpChaosProfile. The lookup is gated on the feature flag AND the active
        // registration count, so it adds nothing to the hot path when no TCP-layer chaos is configured.
        TcpChaosProfile lifecycleProfile = resolveLifecycleProfile(request);

        // L3: HTTP/2 GOAWAY on the response path. Emit before the response head is written so the
        // client learns the connection is going away; the in-flight stream's response still completes.
        if (lifecycleProfile != null && Boolean.TRUE.equals(lifecycleProfile.getHttp2GoAway())) {
            long errorCode = lifecycleProfile.getHttp2GoAwayErrorCode() != null ? lifecycleProfile.getHttp2GoAwayErrorCode() : 0L;
            long lastStreamId = lifecycleProfile.getHttp2GoAwayLastStreamId() != null ? lifecycleProfile.getHttp2GoAwayLastStreamId() : -1L;
            Http2GoAwayEmitter.emit(ctx, lastStreamId, errorCode);
            // GOAWAY is benign (graceful drain signal) so it is NOT counted toward the auto-halt window.
        }

        // L1: mid-stream RST — write the response head then force a TCP RST (SO_LINGER 0 + forced
        // close) instead of a clean FIN, so the client sees "connection reset" mid-stream. This is a
        // destructive fault, so it records a "drop" toward the chaos auto-halt circuit-breaker.
        if (lifecycleProfile != null && Boolean.TRUE.equals(lifecycleProfile.getResetMidResponse())) {
            writeHeadThenReset(ctx, response);
            return;
        }

        Delay chunkDelay = connectionOptions != null ? connectionOptions.getChunkDelay() : null;
        Integer chunkSize = connectionOptions != null ? connectionOptions.getChunkSize() : null;
        if (chunkDelay != null && chunkSize != null && chunkSize > 0) {
            writeChunkedResponseWithDelay(ctx, response, connectionOptions, closeChannel, chunkDelay, lifecycleProfile);
        } else {
            ChannelFuture channelFuture = ctx.writeAndFlush(response);
            addCloseSocketListener(channelFuture, connectionOptions, closeChannel, lifecycleProfile);
        }
    }

    /**
     * Resolve the host-scoped {@link TcpChaosProfile} for connection-lifecycle response-path faults,
     * keyed on the request's {@code Host} header (the mocked service identity), mirroring the
     * host-keyed lookup used by {@code TcpChaosHandler}. Returns {@code null} (zero hot-path cost)
     * when the feature is disabled or no TCP-layer chaos is registered.
     */
    private TcpChaosProfile resolveLifecycleProfile(HttpRequest request) {
        if (request == null || !configuration.connectionLifecycleChaosEnabled()) {
            return null;
        }
        TcpChaosRegistry registry = TcpChaosRegistry.getInstance();
        if (registry.activeCount() == 0) {
            return null;
        }
        String host = request.getFirstHeader("host");
        if (host == null || host.isEmpty()) {
            return null;
        }
        return registry.get(host);
    }

    /**
     * Write the response head, then force a TCP RST instead of a clean FIN once the head has been
     * flushed: set {@code SO_LINGER 0} and {@code closeForcibly()} (mechanism from
     * {@code TcpChaosHandler}). Records a "drop" toward the auto-halt circuit-breaker so a RST storm
     * trips the breaker (which also resets the TcpChaosRegistry).
     */
    private void writeHeadThenReset(final ChannelHandlerContext ctx, HttpResponse response) {
        ChannelFuture headFuture = ctx.writeAndFlush(response);
        if (configuration.connectionLifecycleAutoHaltCountsRst()) {
            // "drop" is a destructive fault type, so recordError runs the auto-halt evaluation.
            Metrics.incrementHttpChaosInjected("drop");
        }
        headFuture.addListener((ChannelFutureListener) future -> forceReset(future.channel()));
    }

    /**
     * Force a TCP RST on the underlying connection: {@code SO_LINGER 0} makes the subsequent close
     * send an RST rather than a clean FIN, then {@code close()} closes the socket — which now aborts
     * with an RST because of the zero linger. This matches the proven RST mechanism in
     * {@code TcpChaosHandler} ({@code setOption(SO_LINGER, 0)} + {@code close()}), avoiding the
     * {@code Unsafe} API. Pending writes are aborted; their buffers are released by Netty on close.
     *
     * <p><b>Multiplex parent walk.</b> {@code resetMidResponse} exists to simulate a genuine socket
     * abort. On the HTTP/2 multiplex pipeline the response head is written on a per-stream
     * {@link Http2StreamChannel} <em>child</em> channel. Setting {@code SO_LINGER} there does nothing
     * at all — {@code DefaultChannelConfig.setOption} returns {@code false} for an option it does not
     * know rather than throwing, so there is not even an exception to notice — and the child's
     * {@code close()} only emits {@code RST_STREAM} for that one stream. Resetting the child would
     * therefore silently degrade the fault into an ordinary stream reset. When the
     * channel is an {@code Http2StreamChannel} we therefore walk up to its parent connection channel
     * (the same parent-walk pattern {@link Http2GoAwayEmitter} uses) and force the RST there, killing
     * the real TCP connection and every concurrent stream on it.
     *
     * <p>The guard is deliberately on the {@code Http2StreamChannel} <em>type</em>, not on
     * {@code parent() != null}: for an ordinary HTTP/1.1 accepted socket channel {@code parent()} is
     * the server <em>listening</em> socket, and resetting that would shut the whole server down. An
     * HTTP/1.1 socket channel and a connection-level HTTP/2 channel are both reset directly.
     */
    private void forceReset(io.netty.channel.Channel channel) {
        io.netty.channel.Channel target = channel;
        if (channel instanceof Http2StreamChannel && channel.parent() != null) {
            // multiplex stream child — reset the parent TCP connection, not this one stream
            target = channel.parent();
        }
        try {
            target.config().setOption(ChannelOption.SO_LINGER, 0);
        } catch (Exception exception) {
            // The parent connection channel (and an HTTP/1.1 socket channel) is a real socket where
            // SO_LINGER applies, so a failure here means the intended RST degraded to a clean FIN — a
            // silently-ineffective destructive fault. Log it rather than swallowing: the connection
            // still closes below, but the client sees an orderly shutdown instead of "connection
            // reset", which is exactly the kind of silent no-op this fault must not become.
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                mockServerLogger.logEvent(new LogEntry()
                    .setLogLevel(WARN)
                    .setMessageFormat("unable to set SO_LINGER 0 for resetMidResponse chaos on channel "
                        + target + " - the connection will close with a clean FIN rather than a TCP RST")
                    .setThrowable(exception));
            }
        }
        target.close();
    }

    private void writeChunkedResponseWithDelay(
        final ChannelHandlerContext ctx,
        HttpResponse response,
        ConnectionOptions connectionOptions,
        boolean closeChannel,
        Delay chunkDelay,
        TcpChaosProfile lifecycleProfile
    ) {
        List<DefaultHttpObject> httpObjects = new org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse(mockServerLogger)
            .mapMockServerResponseToNettyResponse(response);
        if (httpObjects.size() <= 1) {
            ChannelFuture channelFuture = ctx.writeAndFlush(response);
            addCloseSocketListener(channelFuture, connectionOptions, closeChannel, lifecycleProfile);
            return;
        }
        ChannelFuture headerFuture = ctx.writeAndFlush(httpObjects.get(0));
        headerFuture.addListener(f -> {
            if (!f.isSuccess()) {
                for (int i = 1; i < httpObjects.size(); i++) {
                    ReferenceCountUtil.release(httpObjects.get(i));
                }
                addCloseSocketListener(headerFuture, connectionOptions, closeChannel, lifecycleProfile);
                return;
            }
            long cumulativeDelayMs = 0;
            for (int i = 1; i < httpObjects.size(); i++) {
                final DefaultHttpObject chunk = httpObjects.get(i);
                final boolean isLast = (i == httpObjects.size() - 1);
                cumulativeDelayMs += chunkDelay.sampleValueMillis();
                ctx.executor().schedule(() -> {
                    if (ctx.channel().isActive()) {
                        ChannelFuture chunkFuture = ctx.writeAndFlush(chunk);
                        if (isLast) {
                            addCloseSocketListener(chunkFuture, connectionOptions, closeChannel, lifecycleProfile);
                        }
                    } else {
                        ReferenceCountUtil.release(chunk);
                    }
                }, cumulativeDelayMs, TimeUnit.MILLISECONDS);
            }
        });
    }

    /**
     * Schedule the socket close after the terminating write completes. The close delay is resolved
     * as: the per-expectation {@code ConnectionOptions.closeSocketDelay} when set; otherwise the
     * host-scoped {@code TcpChaosProfile.slowCloseDelay} (L2 connection-lifecycle fault) when a chaos
     * profile is active; otherwise an immediate close. The chaos branch is only ever reached when
     * {@code lifecycleProfile} is non-null, which is the case only when connection-lifecycle chaos is
     * enabled AND a host profile is registered — so the non-chaos path is byte-for-byte unchanged.
     */
    private void addCloseSocketListener(ChannelFuture channelFuture, ConnectionOptions connectionOptions, boolean closeChannel, TcpChaosProfile lifecycleProfile) {
        if (closeChannel || configuration.alwaysCloseSocketConnections()) {
            channelFuture.addListener((ChannelFutureListener) future -> {
                Delay closeSocketDelay = connectionOptions != null ? connectionOptions.getCloseSocketDelay() : null;
                if (closeSocketDelay == null && lifecycleProfile != null) {
                    // L2: host-scoped slow close — linger before the FIN even without a per-expectation
                    // connectionOptions.closeSocketDelay. Falls back to immediate close when unset.
                    closeSocketDelay = lifecycleProfile.getSlowCloseDelay();
                }
                if (closeSocketDelay == null) {
                    disconnectAndCloseChannel(future);
                } else {
                    scheduler.schedule(() -> disconnectAndCloseChannel(future), false, closeSocketDelay);
                }
            });
        }
    }

    private void disconnectAndCloseChannel(ChannelFuture future) {
        future
            .channel()
            .disconnect()
            .addListener(disconnectFuture -> {
                    if (disconnectFuture.isSuccess()) {
                        future
                            .channel()
                            .close()
                            .addListener(closeFuture -> {
                                if (disconnectFuture.isSuccess()) {
                                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                                        mockServerLogger
                                            .logEvent(new LogEntry()
                                                .setLogLevel(TRACE)
                                                .setMessageFormat("disconnected and closed socket " + future.channel().localAddress())
                                            );
                                    }
                                } else {
                                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                                        mockServerLogger
                                            .logEvent(new LogEntry()
                                                .setLogLevel(WARN)
                                                .setMessageFormat("exception closing socket " + future.channel().localAddress())
                                                .setThrowable(disconnectFuture.cause())
                                            );
                                    }
                                }
                            });
                    } else if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                        mockServerLogger
                            .logEvent(new LogEntry()
                                .setLogLevel(WARN)
                                .setMessageFormat("exception disconnecting socket " + future.channel().localAddress())
                                .setThrowable(disconnectFuture.cause()));
                    }
                }
            );
    }

}

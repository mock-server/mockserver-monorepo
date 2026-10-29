package org.mockserver.netty.responsewriter;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
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
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.ConnectionClosing;
import org.mockserver.socket.ConnectionReset;
import org.mockserver.socket.ReadAfterFailedWrite;

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
    // LifeCycle is available). Completed exactly once when this exchange's TERMINAL response write
    // FLUSHES (see completeInFlightOnFlush) -- not when the response is dispatched -- so the drain
    // waits for bytes on the wire rather than for hand-off. The channel-close safety net may fire
    // first instead; the token's own idempotent guard makes either order safe.
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
    public Runnable respondingDirectly() {
        return inFlightRequest != null ? inFlightRequest::complete : super.respondingDirectly();
    }

    @Override
    public void sendResponse(HttpRequest request, HttpResponse response) {
        if (startNanos >= 0) {
            double durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            Metrics.observeRequestDurationSeconds(durationSeconds);
            Metrics.observeRequestDurationByMethodSeconds(durationSeconds, request != null ? request.getMethod("") : null);
        }
        // Release this exchange from the graceful-shutdown drain counter only when its TERMINAL
        // response write has actually flushed to the socket — NOT here at response hand-off. This is
        // still the single funnel for every data-plane response (normal, streaming, chunked,
        // forward/proxy, error, breakpoint-modified), but each of those paths now completes the token
        // from its terminal write's ChannelFuture via completeInFlightOnFlush(...), so
        // drainInFlightRequests() holds stopAsync() open until the bytes are on the wire. Completing
        // at hand-off (the previous behaviour) let the drain counter reach zero while the body was
        // still queued: stopAsync() then tore the event loops down mid-flush and Netty force-closed
        // the channel, truncating the in-flight response (PrematureChannelClosureException on the
        // client). Intermediate writes — the streaming response head and each streamed chunk — must
        // NOT complete the token. The token's idempotent guard and the channel-close safety net still
        // release it exactly once for an exchange that errors, is cancelled, or whose channel dies
        // before any terminal write is attempted.
        if (response.getStreamingBody() != null) {
            writeStreamingResponse(ctx, request, response);
        } else {
            writeAndCloseSocket(ctx, request, response);
        }
        if (isFinalInformationalResponse(response)) {
            // the exchange handlers after HttpServerCodec take any 1xx but 101 for an interim response
            HttpExchangeEndedEvent.fire(ctx);
        }
    }

    /**
     * Writes a response that is one write. Over HTTP/2 a final {@code 1xx} goes out as an interim response, which may
     * not end a stream (RFC 9113 section 8.1), so its stream is reset after it. The future returned is then the
     * reset's: whatever closes the stream once the response is written must find it reset, or it would send its own.
     */
    private static ChannelFuture writeWhole(ChannelHandlerContext ctx, HttpResponse response) {
        if (isFinalInformationalResponseOverHttp2(ctx, response)) {
            ctx.write(withoutChunking(response));
            return ctx.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.NO_ERROR));
        }
        return ctx.writeAndFlush(response);
    }

    /**
     * Netty's HTTP/2 codec takes a {@code 1xx} only as one whole message, and a chunk size would have the response
     * mapped to a head and its chunks.
     */
    private static HttpResponse withoutChunking(HttpResponse response) {
        ConnectionOptions options = response.getConnectionOptions();
        if (options == null || options.getChunkSize() == null) {
            return response;
        }
        return response.shallowClone().withConnectionOptions(new ConnectionOptions()
            .withSuppressContentLengthHeader(options.getSuppressContentLengthHeader())
            .withContentLengthHeaderOverride(options.getContentLengthHeaderOverride())
            .withSuppressConnectionHeader(options.getSuppressConnectionHeader())
            .withKeepAliveOverride(options.getKeepAliveOverride())
            .withCloseSocket(options.getCloseSocket())
            .withCloseSocketDelay(options.getCloseSocketDelay()));
    }

    private static boolean isFinalInformationalResponseOverHttp2(ChannelHandlerContext ctx, HttpResponse response) {
        return ctx.channel() instanceof Http2StreamChannel && isFinalInformationalResponse(response);
    }

    private static boolean isFinalInformationalResponse(HttpResponse response) {
        Integer statusCode = response.getStatusCode();
        return statusCode != null && statusCode >= 100 && statusCode < 200 && statusCode != HttpResponseStatus.SWITCHING_PROTOCOLS.code();
    }

    /**
     * Release this exchange's graceful-shutdown drain token once its terminal response write has
     * flushed. Attaches a listener to the terminal write's {@link ChannelFuture} so
     * {@code LifeCycle.drainInFlightRequests()} holds the event loops open until the bytes are on the
     * wire rather than merely dispatched. The listener fires on success, failure, <em>and</em>
     * cancellation of the write, so a response that errors or whose channel dies still releases its
     * token; the token's own idempotent guard makes this safe alongside the channel-close safety net.
     * When no write was attempted (the channel is already gone, so {@code future} is {@code null}) the
     * token is completed directly — there are no bytes left to flush and the close-future net has
     * nothing to protect. Null-safe when no {@link org.mockserver.netty.InFlightRequest} is present.
     * <p>
     * A terminal write that fails closes the connection: the client may have received part of the response, or
     * none of it, so it can neither reuse the connection nor tell that no response is coming. A connection whose
     * output the failure ended is left to {@link ReadAfterFailedWrite}, which reads what the client sent first. This
     * shares the token's listener, so it adds no allocation; every writer {@code HttpRequestHandler} creates has a token.
     */
    private void completeInFlightOnFlush(ChannelFuture future) {
        if (inFlightRequest == null) {
            return;
        }
        if (future == null) {
            inFlightRequest.complete();
        } else {
            future.addListener((ChannelFutureListener) written -> {
                if (!written.isSuccess() && !ReadAfterFailedWrite.isReadingOn(written.channel())) {
                    written.channel().close();
                }
                inFlightRequest.complete();
            });
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

        // a connection or HTTP/2 stream closed mid-response (a write stall, the client going away) takes no more of the
        // upstream; removed when the stream ends, so a keep-alive connection does not collect one per response
        // a breakpoint CLOSE decision closes the connection itself, so it is not the client leaving
        final java.util.concurrent.atomic.AtomicBoolean closedByBreakpoint = new java.util.concurrent.atomic.AtomicBoolean();
        ChannelFutureListener closeUpstreamIfIncomplete = future -> {
            if (!streamingBody.isCompleted()) {
                if (closedByBreakpoint.get()) {
                    streamingBody.closeUpstream();
                } else {
                    streamingBody.closeUpstreamForClientClose();
                }
            }
        };
        ctx.channel().closeFuture().addListener(closeUpstreamIfIncomplete);

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
                : org.mockserver.uuid.UUIDService.getNonSecureUUID() + "-stream";
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

        // With stream breakpoints active, every frame write (and the end of the stream) is chained
        // behind the previous frame's decision, so frames reach the client in order and a frame
        // still held when the source completes is delivered before the terminating chunk.
        final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>> frameTail =
            streamBreakpointsActive ? new java.util.concurrent.atomic.AtomicReference<>(java.util.concurrent.CompletableFuture.completedFuture(null)) : null;
        final ChannelFutureListener releaseHeldFramesOnClose = streamBreakpointsActive
            ? future -> org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().releaseStream(streamId)
            : null;
        if (releaseHeldFramesOnClose != null) {
            ctx.channel().closeFuture().addListener(releaseHeldFramesOnClose);
        }

        Runnable finishStream = () -> {
            if (ctx.channel().isActive()) {
                boolean closeChannel;
                ConnectionOptions connectionOptions = response.getConnectionOptions();
                if (connectionOptions != null && connectionOptions.getCloseSocket() != null) {
                    closeChannel = connectionOptions.getCloseSocket();
                } else {
                    closeChannel = !(request.isKeepAlive() != null && request.isKeepAlive());
                }
                if (closeChannel) {
                    markClosingUnlessAnnounced(ctx, response);
                }
                // The terminating LastHttpContent is the terminal write of a streaming response —
                // release the in-flight token when it flushes so the drain waits for the full
                // stream to reach the wire (on a keep-alive connection the channel is NOT closed
                // here, so the close-future net would otherwise not release it until much later).
                ChannelFuture lastFuture = ctx.writeAndFlush(lastContentWithTrailers(response));
                lastFuture.addListener(future -> {
                    if (closeChannel || configuration.alwaysCloseSocketConnections()) {
                        ctx.close();
                    }
                });
                completeInFlightOnFlush(lastFuture);
            } else {
                // Stream completed but the channel is already gone — nothing left to flush.
                completeInFlightOnFlush(null);
            }
        };

        java.util.function.Consumer<Throwable> failStream = error -> {
            if (error instanceof StreamingBody.StreamAbortedException) {
                // no terminating chunk, so the client sees an incomplete response (HTTP/2: a reset stream);
                // closing fails and releases the writes still queued
                completeInFlightOnFlush(ctx.close());
            } else if (ctx.channel().isActive()) {
                markClosingUnlessAnnounced(ctx, response);
                // Error terminates the stream: the LastHttpContent + close is the terminal write.
                ChannelFuture errorFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                errorFuture.addListener(future -> ctx.close());
                completeInFlightOnFlush(errorFuture);
            } else {
                completeInFlightOnFlush(null);
            }
        };

        // Subscribe to the streaming body to forward chunks as they arrive.
        // After each chunk write completes, call streamingBody.chunkWritten(bytes), which
        // requests the next upstream read once the backlog has drained — this implements
        // backpressure so a slow client does not cause unbounded buffering on the server channel.
        streamingBody.subscribe(
            // onChunk
            chunk -> {
                final int chunkSize = chunk.readableBytes();
                if (!ctx.channel().isActive()) {
                    // The client has gone, so nothing will take the rest of the stream
                    streamingBody.closeUpstreamForClientClose();
                    streamingBody.chunkWritten(chunkSize);
                    return;
                }

                if (!streamBreakpointsActive) {
                    // Default-off fast path: write the frame immediately (no interception)
                    DefaultHttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer(chunk));
                    ctx.writeAndFlush(content).addListener(future -> streamingBody.chunkWritten(chunkSize));
                    return;
                }

                // --- Stream-frame breakpoint path ---
                // Copy the chunk bytes (the ByteBuf is owned by StreamingBody and released after onChunk returns)
                byte[] chunkBytes = new byte[chunk.readableBytes()];
                chunk.getBytes(chunk.readerIndex(), chunkBytes);

                int seq = StreamFrameBreakpointRegistry.getInstance()
                    .nextSequenceNumber(streamId);
                java.util.concurrent.CompletableFuture<StreamFrameDecision> wsFuture =
                    org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().dispatchFrame(
                        breakpointClientId, streamBreakpointMatcher.getId(), streamId, seq,
                        PausedStreamFrame.Direction.OUTBOUND,
                        org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM,
                        chunkBytes, reqMethod, reqPath,
                        null, wsRegistry,
                        configuration, mockServerLogger
                    );
                // Cap reached or client not connected: the frame is not held, but is still
                // written in order behind any frame that is
                final java.util.concurrent.CompletableFuture<StreamFrameDecision> decisionFuture = wsFuture != null
                    ? wsFuture
                    : java.util.concurrent.CompletableFuture.completedFuture(StreamFrameDecision.continueFrame());

                // We do NOT call streamingBody.chunkWritten(chunkSize) until the frame is written
                // or discarded — this stops the upstream from sending more chunks (backpressure).
                final java.util.concurrent.CompletableFuture<Void> written = new java.util.concurrent.CompletableFuture<>();
                frameTail.getAndSet(written)
                    .thenCombine(decisionFuture, (previousWritten, decision) -> decision)
                    .thenAccept(decision -> ctx.channel().eventLoop().execute(() -> {
                        try {
                            applyStreamFrameDecision(ctx, streamingBody, decision, chunkBytes, chunkSize, closedByBreakpoint);
                        } finally {
                            written.complete(null);
                        }
                    }));
            },
            // onComplete
            () -> {
                ctx.channel().closeFuture().removeListener(closeUpstreamIfIncomplete);
                if (streamBreakpointsActive) {
                    frameTail.get().whenComplete((ignored, throwable) -> ctx.channel().eventLoop().execute(() -> {
                        ctx.channel().closeFuture().removeListener(releaseHeldFramesOnClose);
                        StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                        if (closedByBreakpoint.get()) {
                            // the CLOSE decision already ended the response
                            return;
                        }
                        finishStream.run();
                    }));
                } else {
                    finishStream.run();
                }
            },
            // onError
            error -> {
                ctx.channel().closeFuture().removeListener(closeUpstreamIfIncomplete);
                if (streamBreakpointsActive) {
                    // the response cannot be completed, so frames still held can never be delivered
                    org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().releaseStream(streamId);
                    frameTail.get().whenComplete((ignored, throwable) -> ctx.channel().eventLoop().execute(() -> {
                        ctx.channel().closeFuture().removeListener(releaseHeldFramesOnClose);
                        StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                        if (closedByBreakpoint.get()) {
                            return;
                        }
                        failStream.accept(error);
                    }));
                } else {
                    failStream.accept(error);
                }
            }
        );
    }

    /**
     * Applies a stream-frame breakpoint decision on the client channel's event loop. Every
     * route reports the frame's bytes written (or discarded) to the streaming body, except
     * CLOSE, which ends the response and closes the upstream.
     */
    private void applyStreamFrameDecision(ChannelHandlerContext ctx, StreamingBody streamingBody, StreamFrameDecision decision,
                                          byte[] chunkBytes, int chunkSize, java.util.concurrent.atomic.AtomicBoolean closedByBreakpoint) {
        if (closedByBreakpoint.get()) {
            streamingBody.chunkWritten(chunkSize);
            return;
        }
        if (!ctx.channel().isActive()) {
            streamingBody.closeUpstreamForClientClose();
            streamingBody.chunkWritten(chunkSize);
            return;
        }
        switch (decision.getAction()) {
            case CONTINUE: {
                DefaultHttpContent content = new DefaultHttpContent(Unpooled.wrappedBuffer(chunkBytes));
                ctx.writeAndFlush(content).addListener(future -> streamingBody.chunkWritten(chunkSize));
                break;
            }
            case MODIFY: {
                DefaultHttpContent content = new DefaultHttpContent(Unpooled.wrappedBuffer(decision.getReplacementBody()));
                ctx.writeAndFlush(content).addListener(future -> streamingBody.chunkWritten(chunkSize));
                break;
            }
            case DROP: {
                // Discard the frame — do not write anything to the client
                streamingBody.chunkWritten(chunkSize);
                break;
            }
            case INJECT: {
                // Write the original frame, then the injected one, both before the next frame
                ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(chunkBytes)));
                ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(decision.getInjectedBody())))
                    .addListener(future -> streamingBody.chunkWritten(chunkSize));
                break;
            }
            case CLOSE: {
                // End the stream: send LastHttpContent and close. This is the terminal write of
                // the response — release the in-flight token once it flushes. The upstream is
                // closed once it has: nothing will take the rest of the stream, and closing it
                // first would abort the body before this flush.
                closedByBreakpoint.set(true);
                ChannelFuture closeFrameFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                closeFrameFuture.addListener(future -> {
                    ctx.close();
                    streamingBody.closeUpstream();
                    // Do NOT request more — stream is ended
                });
                completeInFlightOnFlush(closeFrameFuture);
                break;
            }
            default: {
                // Unrecognised action — log a warning and request more to avoid
                // hanging the stream if a future action type is added without
                // updating this switch.
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                    mockServerLogger.logEvent(new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat("unrecognised stream frame breakpoint action:{}")
                        .setArguments(decision.getAction())
                    );
                }
                streamingBody.chunkWritten(chunkSize);
                break;
            }
        }
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

        // L1: write the whole response then reset the connection (a TCP RST, not a clean FIN), so a
        // client reading on past it sees "connection reset". This is a destructive fault, so it
        // records a "drop" toward the chaos auto-halt circuit-breaker.
        if (lifecycleProfile != null && Boolean.TRUE.equals(lifecycleProfile.getResetMidResponse())) {
            if (!(ctx.channel() instanceof Http2StreamChannel)) {
                // the reset is issued beneath the pipelining gate, once the response is written
                ConnectionClosing.mark(ctx.channel());
            }
            writeThenReset(ctx, response);
            return;
        }
        if (closeChannel) {
            markClosingUnlessAnnounced(ctx, response);
        }

        Delay chunkDelay = connectionOptions != null ? connectionOptions.getChunkDelay() : null;
        Integer chunkSize = connectionOptions != null ? connectionOptions.getChunkSize() : null;
        // over HTTP/2 a final 1xx is its headers alone, written whole (writeWhole)
        if (chunkDelay != null && chunkSize != null && chunkSize > 0 && !isFinalInformationalResponseOverHttp2(ctx, response)) {
            writeChunkedResponseWithDelay(ctx, response, connectionOptions, closeChannel, chunkDelay, lifecycleProfile);
        } else {
            // Normal / error / breakpoint-modified response: this single writeAndFlush IS the whole
            // response, so it is the terminal write — complete the in-flight token when it flushes.
            ChannelFuture channelFuture = writeWhole(ctx, response);
            addCloseSocketListener(channelFuture, connectionOptions, closeChannel, lifecycleProfile);
            completeInFlightOnFlush(channelFuture);
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
     * Write the whole response, then reset the connection with a TCP RST instead of a clean FIN once
     * it has been flushed, via {@link ConnectionReset#reset}: on HTTP/2 multiplex it resets the
     * parent connection, over TLS it sends no {@code close_notify} first, and on a CONNECT/SOCKS
     * tunnel's loopback it resets the tunnel's client connection too. Records a "drop" toward the
     * auto-halt circuit-breaker so a RST storm trips the breaker (which also resets the TcpChaosRegistry).
     */
    private void writeThenReset(final ChannelHandlerContext ctx, HttpResponse response) {
        ChannelFuture headFuture = ctx.writeAndFlush(response);
        if (configuration.connectionLifecycleAutoHaltCountsRst()) {
            // "drop" is a destructive fault type, so recordError runs the auto-halt evaluation.
            Metrics.incrementHttpChaosInjected("drop");
        }
        headFuture.addListener((ChannelFutureListener) future -> ConnectionReset.reset(future.channel()));
        // The whole response is the only write on the mid-response-RST fault path (nothing follows
        // it — the connection is reset), so it is terminal: release the in-flight token once it has
        // flushed (or failed). The forced reset that follows closes the channel; the close-future net
        // would also release the token, but completing on the head write keeps the drain semantics
        // identical to every other path (flush, not dispatch).
        completeInFlightOnFlush(headFuture);
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
            // Nothing to chunk — a single writeAndFlush of the whole response is the terminal write.
            ChannelFuture channelFuture = ctx.writeAndFlush(response);
            addCloseSocketListener(channelFuture, connectionOptions, closeChannel, lifecycleProfile);
            completeInFlightOnFlush(channelFuture);
            return;
        }
        ChannelFuture headerFuture = ctx.writeAndFlush(httpObjects.get(0));
        headerFuture.addListener(f -> {
            if (!f.isSuccess()) {
                for (int i = 1; i < httpObjects.size(); i++) {
                    ReferenceCountUtil.release(httpObjects.get(i));
                }
                addCloseSocketListener(headerFuture, connectionOptions, closeChannel, lifecycleProfile);
                // The header write failed, so no chunks will be sent: this failed write is terminal.
                // completeInFlightOnFlush fires on the (already-failed) future, releasing the token.
                completeInFlightOnFlush(headerFuture);
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
                            // The final chunk is the terminal write of a chunked response — release
                            // the in-flight token once it flushes so the drain waits for the whole
                            // body, not just the head.
                            addCloseSocketListener(chunkFuture, connectionOptions, closeChannel, lifecycleProfile);
                            completeInFlightOnFlush(chunkFuture);
                        }
                    } else {
                        ReferenceCountUtil.release(chunk);
                        if (isLast) {
                            // Channel died before the final chunk could be written: no bytes remain to
                            // flush, so release the token directly (the close-future net also covers
                            // this, but completing here keeps the token from lingering until close).
                            completeInFlightOnFlush(null);
                        }
                    }
                }, cumulativeDelayMs, TimeUnit.MILLISECONDS);
            }
        });
    }

    /**
     * Tells an HTTP/1.1 connection's pipelining gate, before the response is written, that the connection will close
     * after it: the close is issued once the write completes, possibly off the event loop or after a delay, so the gate
     * could release a pipelined request first. A response that says {@code Connection: close} already tells it.
     */
    private static void markClosingUnlessAnnounced(ChannelHandlerContext ctx, HttpResponse response) {
        if (!(ctx.channel() instanceof Http2StreamChannel) && !"close".equalsIgnoreCase(response.getFirstHeader(HttpHeaderNames.CONNECTION.toString()))) {
            ConnectionClosing.mark(ctx.channel());
        }
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

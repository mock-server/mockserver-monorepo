package org.mockserver.netty.http3;

import com.google.protobuf.Descriptors;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcResponseStatusResolver;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.GrpcTimeout;
import org.mockserver.grpc.GrpcStreamMessageEncoder;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.breakpoint.PausedStreamFrame;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.mock.breakpoint.StreamFrameDecision;
import org.mockserver.model.Delay;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.responsewriter.GrpcStreamResponseWriter;
import org.mockserver.responsewriter.ResponseWriter;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

/**
 * A {@link ResponseWriter} that writes gRPC responses over HTTP/3 with correct
 * gRPC wire framing: initial HEADERS ({@code :status=200},
 * {@code content-type=application/grpc}), DATA (gRPC length-prefixed message),
 * and trailing HEADERS ({@code grpc-status}, {@code grpc-message}, plus any trailing metadata the
 * expectation authored with {@code withTrailer(...)}).
 * <p>
 * This follows the gRPC-over-HTTP/3 convention (same as HTTP/2): the
 * {@code grpc-status} is conveyed in a <strong>trailing HEADERS frame</strong>
 * at end-of-stream, which gRPC clients require. The initial HEADERS frame does
 * NOT contain grpc-status.
 * <p>
 * For error responses without a body, the "trailers-only" pattern is used:
 * a single HEADERS frame containing both {@code :status=200} and
 * {@code grpc-status} (no DATA frame). Any user-authored trailers ride that same frame, which is
 * the Trailers-Only form's defined shape ({@code HTTP-Status Content-Type Trailers}) and keeps the
 * frame terminal.
 * <p>
 * The gRPC service and method names are captured from the original request
 * (where {@link GrpcHttp3Adapter} places them as {@code x-grpc-service} and
 * {@code x-grpc-method} headers) rather than from the response, because the
 * matching pipeline does not propagate these internal headers to the matched
 * response.
 */
public class Http3GrpcResponseWriter extends ResponseWriter implements GrpcStreamResponseWriter {

    private final ChannelHandlerContext ctx;
    private final GrpcProtoDescriptorStore descriptorStore;
    private final String grpcService;
    private final String grpcMethod;
    private final WebSocketClientRegistry webSocketClientRegistry;
    /**
     * Guards the stream against two terminal writes: the matched response and an elapsed
     * {@code grpc-timeout} deadline race, and exactly one must win.
     */
    private final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Stream lifecycle, so starting a stream and an elapsed deadline are one transition: otherwise the deadline could
     * write a trailers-only frame (with {@code :status}) and the stream its own initial HEADERS, two {@code :status}
     * frames on one stream. Every transition now runs on the stream's event loop, as the writer's entry points hop
     * there first; the state stays atomic as a defence should a caller ever reach it from another thread.
     */
    private enum StreamState { IDLE, STREAMING, COMPLETED }

    private final java.util.concurrent.atomic.AtomicReference<StreamState> streamState =
        new java.util.concurrent.atomic.AtomicReference<>(StreamState.IDLE);
    private io.netty.util.concurrent.ScheduledFuture<?> deadlineFuture;

    public Http3GrpcResponseWriter(
        Configuration configuration,
        MockServerLogger mockServerLogger,
        ChannelHandlerContext ctx,
        GrpcProtoDescriptorStore descriptorStore,
        String grpcService,
        String grpcMethod
    ) {
        this(configuration, mockServerLogger, ctx, descriptorStore, grpcService, grpcMethod, null);
    }

    public Http3GrpcResponseWriter(
        Configuration configuration,
        MockServerLogger mockServerLogger,
        ChannelHandlerContext ctx,
        GrpcProtoDescriptorStore descriptorStore,
        String grpcService,
        String grpcMethod,
        WebSocketClientRegistry webSocketClientRegistry
    ) {
        super(configuration, mockServerLogger);
        this.ctx = ctx;
        this.descriptorStore = descriptorStore;
        this.grpcService = grpcService;
        this.grpcMethod = grpcMethod;
        this.webSocketClientRegistry = webSocketClientRegistry;
    }

    /**
     * Honours the client's {@code grpc-timeout} by scheduling a DEADLINE_EXCEEDED response.
     * <p>
     * Each HTTP/3 request is its own {@link QuicStreamChannel}, so the timer is naturally
     * per-stream and dies with the stream. {@link #completed} makes the deadline and the real
     * response mutually exclusive, so a {@code Delay} that outruns the client's deadline cannot
     * write a second response onto a stream that already carries terminal trailers.
     */
    public void scheduleDeadline(HttpRequest request) {
        Long timeoutNanos = GrpcTimeout.parseNanos(request);
        if (timeoutNanos == null) {
            return;
        }
        // Cancel if the stream closes first. Without this an abandoned QUIC stream carrying a very
        // long grpc-timeout (the format saturates at ~292 years) leaves a ScheduledFutureTask in
        // the shared event-loop queue forever -- unauthenticated and uncapped, so a client could
        // open and reset streams to accumulate them. The HTTP/1.1 and HTTP/2 path already cancels
        // via channelInactive; this was the omission.
        ctx.channel().closeFuture().addListener(closeFuture -> {
            if (deadlineFuture != null) {
                deadlineFuture.cancel(false);
                deadlineFuture = null;
            }
        });
        deadlineFuture = ctx.channel().eventLoop().schedule(() -> {
            if (!claimForDeadline()) {
                return;
            }
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("gRPC deadline elapsed over HTTP/3 for {} - returning DEADLINE_EXCEEDED")
                        .setArguments(request.getPath())
                );
            }
            String deadlineMessage = GrpcTimeout.deadlineExceededMessage(timeoutNanos);
            // Claim the stream for the deadline. If it was IDLE the stream never started, so a
            // trailers-only frame (carrying :status) is correct; if it was STREAMING the initial
            // HEADERS are already on the wire and only a TRAILING HEADERS frame may follow.
            StreamState priorState = streamState.getAndSet(StreamState.COMPLETED);
            if (priorState == StreamState.COMPLETED) {
                return;
            }
            if (priorState == StreamState.STREAMING) {
                // mid-stream: the initial HEADERS are already on the wire, so terminate with a
                // TRAILING HEADERS frame rather than a trailers-only frame
                DefaultHttp3HeadersFrame trailers = GrpcHttp3Adapter.buildTrailingHeadersFrame(
                    String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode()),
                    deadlineMessage
                );
                ctx.writeAndFlush(trailers).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            } else {
                writeTrailersOnly(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED, deadlineMessage);
            }
        }, timeoutNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Claims the stream for an elapsed deadline, on the stream's event loop.
     *
     * @return false when a response or an error already claimed it
     */
    boolean claimForDeadline() {
        return completed.compareAndSet(false, true);
    }

    private void writeTrailersOnly(GrpcStatusMapper.GrpcStatusCode statusCode, String message) {
        DefaultHttp3HeadersFrame trailersOnly = GrpcHttp3Adapter.buildTrailersOnlyFrame(
            String.valueOf(statusCode.getCode()), message
        );
        ctx.writeAndFlush(trailersOnly).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
    }

    @Override
    public void sendResponse(HttpRequest request, HttpResponse response) {
        if (!ctx.executor().inEventLoop()) {
            // the deadline and the response-once guard are then only ever touched on the stream's event loop
            Http3ResponseWriter.executeOnStreamEventLoop(ctx, mockServerLogger, () -> sendResponse(request, response));
            return;
        }
        if (!completed.compareAndSet(false, true)) {
            // the deadline already fired and terminated this stream -- dropping the late response
            // avoids writing a second response onto a stream that already ended
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("dropping gRPC response over HTTP/3 for a stream that already"
                            + " ended with DEADLINE_EXCEEDED - the delay outlasted the client's grpc-timeout")
                );
            }
            return;
        }
        if (deadlineFuture != null) {
            deadlineFuture.cancel(false);
            deadlineFuture = null;
        }
        HttpResponse toWrite = response != null ? response : HttpResponse.notFoundResponse();

        if (grpcService != null && !grpcService.isEmpty()
            && grpcMethod != null && !grpcMethod.isEmpty()
            && descriptorStore != null && descriptorStore.hasServices()) {
            writeGrpcResponse(request, toWrite, grpcService, grpcMethod);
        } else {
            // No descriptor-based conversion: the body stays as-is (binary or
            // whatever the expectation returned). Frame grpc-status correctly
            // in trailing HEADERS.
            writePassthroughGrpcResponse(toWrite);
        }
    }

    /**
     * Write a gRPC response with proper trailing HEADERS framing.
     * The response body is converted from JSON to gRPC-framed protobuf.
     */
    private void writeGrpcResponse(
        HttpRequest request, HttpResponse response,
        String serviceName, String methodName
    ) {
        try {
            GrpcHttp3Adapter.GrpcResponseParts parts = GrpcHttp3Adapter.transformGrpcResponse(
                response, serviceName, methodName, descriptorStore
            );

            if (parts.hasBody()) {
                // Full response: initial HEADERS + DATA + trailing HEADERS
                DefaultHttp3HeadersFrame initialHeaders = GrpcHttp3Adapter.buildInitialHeadersFrame(response);
                DefaultHttp3DataFrame dataFrame = GrpcHttp3Adapter.buildDataFrame(parts.grpcFrameBytes());
                DefaultHttp3HeadersFrame trailers = GrpcHttp3Adapter.buildTrailingHeadersFrame(
                    parts.grpcStatus(), parts.grpcMessage(), response
                );

                ctx.write(initialHeaders);
                ctx.write(dataFrame);
                ctx.writeAndFlush(trailers)
                    .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            } else {
                // Trailers-only: single HEADERS frame with grpc-status
                DefaultHttp3HeadersFrame trailersOnly = GrpcHttp3Adapter.buildTrailersOnlyFrame(
                    parts.grpcStatus(), parts.grpcMessage(), response
                );
                ctx.writeAndFlush(trailersOnly)
                    .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setHttpRequest(request)
                        .setMessageFormat("failed to encode gRPC response over HTTP/3 for {}/{}:{}")
                        .setArguments(serviceName, methodName, e.getMessage())
                        .setThrowable(e)
                );
            }
            writeErrorResponse(
                GrpcStatusMapper.GrpcStatusCode.INTERNAL,
                "failed to encode gRPC response: " + e.getMessage()
            );
        }
    }

    /**
     * Write a passthrough gRPC response -- the response already has grpc-status
     * as a header (from a raw expectation or chaos handler), so we just frame
     * it correctly with trailing HEADERS.
     */
    private void writePassthroughGrpcResponse(HttpResponse response) {
        // Use the SHARED resolver rather than reading grpc-status from the headers and defaulting
        // to "0". Two defects came from that default:
        //  - an expectation authored with withTrailer("grpc-status", ...) -- which the consumer
        //    docs recommend -- was reported as OK, so the same expectation gave opposite results
        //    over HTTP/2 and HTTP/3;
        //  - an UNMATCHED request fabricated a success: this branch is taken whenever no descriptor
        //    is loaded, so the 404 notFoundResponse was written out as grpc-status 0 with the 404
        //    body as a DATA frame. That is the direct twin of the HTTP/2 defect from #2419.
        GrpcResponseStatusResolver.ResolvedStatus resolved = GrpcResponseStatusResolver.resolve(response);
        String grpcStatus = resolved.code();
        String grpcMessage = resolved.message();

        // A transport failure's body is not a gRPC message (it is an error page or a mismatch
        // diagnostic), so the status is the whole response.
        byte[] bodyBytes = resolved.isTransportFailure() ? null : response.getBodyAsRawBytes();
        if (bodyBytes != null && bodyBytes.length > 0) {
            DefaultHttp3HeadersFrame initialHeaders = GrpcHttp3Adapter.buildInitialHeadersFrame(response);
            DefaultHttp3DataFrame dataFrame = GrpcHttp3Adapter.buildDataFrame(bodyBytes);
            DefaultHttp3HeadersFrame trailers = GrpcHttp3Adapter.buildTrailingHeadersFrame(
                grpcStatus, grpcMessage, response
            );

            ctx.write(initialHeaders);
            ctx.write(dataFrame);
            ctx.writeAndFlush(trailers)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        } else {
            DefaultHttp3HeadersFrame trailersOnly = GrpcHttp3Adapter.buildTrailersOnlyFrame(
                grpcStatus, grpcMessage, response
            );
            ctx.writeAndFlush(trailersOnly)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }
    }

    /**
     * Write a gRPC error as a trailers-only response over HTTP/3.
     */
    void writeErrorResponse(GrpcStatusMapper.GrpcStatusCode statusCode, String message) {
        DefaultHttp3HeadersFrame trailersOnly = GrpcHttp3Adapter.buildTrailersOnlyFrame(
            String.valueOf(statusCode.getCode()), message
        );
        ctx.writeAndFlush(trailersOnly)
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
    }

    /**
     * Ends the stream with {@code statusCode} unless a response, a stream or the deadline has already claimed it.
     */
    void writeErrorResponseIfUnanswered(GrpcStatusMapper.GrpcStatusCode statusCode, String message) {
        // the stream state first: claiming completed while a stream is running would stop the deadline ending it
        if (!streamState.compareAndSet(StreamState.IDLE, StreamState.COMPLETED) || !completed.compareAndSet(false, true)) {
            return;
        }
        if (deadlineFuture != null) {
            deadlineFuture.cancel(false);
            deadlineFuture = null;
        }
        writeErrorResponse(statusCode, message);
    }

    // ------------------------------------------------------------------
    // Server-streaming gRPC over HTTP/3 (GrpcStreamResponseWriter)
    // ------------------------------------------------------------------

    /**
     * Write a server-streaming gRPC response over HTTP/3: an initial HEADERS frame
     * ({@code :status=200}, {@code content-type=application/grpc}, plus any configured
     * headers), one DATA frame per message honouring per-message {@link Delay}, then a
     * trailing HEADERS frame carrying {@code grpc-status} / {@code grpc-message}, followed
     * by a QUIC stream output shutdown (or connection close when configured).
     * <p>
     * Mirrors the HTTP/2 {@link org.mockserver.mock.action.http.GrpcStreamResponseActionHandler}
     * scheduling pattern, reusing {@link GrpcStreamMessageEncoder} for byte-identical framing.
     * Delays are scheduled on the QUIC stream's own event-loop executor so writes stay ordered.
     * <p>
     * When a RESPONSE_STREAM breakpoint matcher matches, each outbound DATA frame is parked in the
     * {@link StreamFrameBreakpointRegistry} (with stream-id suffix {@code -h3-grpc-stream})
     * before writing. Frame bytes are already {@code byte[]} from
     * {@link GrpcStreamMessageEncoder#encode} -- no ByteBuf is retained across the hold period.
     * The decision future callback runs on the QUIC stream's event loop via
     * {@code ctx.channel().eventLoop().execute()}, preserving frame ordering and respecting
     * HTTP/3/QUIC flow control (Netty's outbound write path handles QUIC flow-control windows
     * transparently). On stream close or error, all held frames are evicted.
     */
    @Override
    public void writeGrpcStreamResponse(GrpcStreamResponse grpcStreamResponse, HttpRequest request) {
        if (!ctx.executor().inEventLoop()) {
            Http3ResponseWriter.executeOnStreamEventLoop(ctx, mockServerLogger, () -> writeGrpcStreamResponse(grpcStreamResponse, request));
            return;
        }
        Descriptors.MethodDescriptor methodDescriptor = resolveMethodDescriptor();

        // Pre-compute breakpoint matcher + WS dispatch decision once per stream (CPX-01)
        final org.mockserver.mock.breakpoint.BreakpointMatcher streamBreakpointMatcher = org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().findMatch(request, org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM);
        final boolean streamBreakpointsActive = streamBreakpointMatcher != null;
        final String streamId;
        final String reqMethod;
        final String reqPath;
        final boolean useWsDispatch;
        final String breakpointClientId;
        if (streamBreakpointsActive) {
            streamId = (request.getLogCorrelationId() != null
                ? request.getLogCorrelationId() : org.mockserver.uuid.UUIDService.getNonSecureUUID()) + "-h3-grpc-stream";
            reqMethod = request.getMethod() != null ? request.getMethod().getValue() : null;
            reqPath = request.getPath() != null ? request.getPath().getValue() : null;
            breakpointClientId = streamBreakpointMatcher.getClientId();
            useWsDispatch = breakpointClientId != null && webSocketClientRegistry != null;
        } else {
            streamId = null;
            reqMethod = null;
            reqPath = null;
            useWsDispatch = false;
            breakpointClientId = null;
        }

        // Single atomic transition: either this claims the stream for streaming, or the deadline
        // already claimed it and nothing further may be written.
        if (!streamState.compareAndSet(StreamState.IDLE, StreamState.STREAMING)) {
            return;
        }

        DefaultHttp3HeadersFrame initialHeaders = GrpcHttp3Adapter.buildInitialHeadersFrame();
        addConfiguredHeaders(initialHeaders, grpcStreamResponse.getHeaders());
        ctx.write(initialHeaders);
        ctx.flush();

        List<GrpcStreamMessage> messages = grpcStreamResponse.getMessages();
        if (messages != null && !messages.isEmpty()) {
            scheduleStreamMessages(messages, 0, grpcStreamResponse, methodDescriptor, request,
                streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId,
                streamBreakpointMatcher != null ? streamBreakpointMatcher.getId() : null);
        } else {
            finishGrpcStream(grpcStreamResponse, streamBreakpointsActive, streamId);
        }
    }

    private Descriptors.MethodDescriptor resolveMethodDescriptor() {
        if (grpcService != null && !grpcService.isEmpty()
            && grpcMethod != null && !grpcMethod.isEmpty()
            && descriptorStore != null && descriptorStore.hasServices()) {
            return descriptorStore.getMethod(grpcService, grpcMethod);
        }
        return null;
    }

    private void addConfiguredHeaders(DefaultHttp3HeadersFrame frame, Headers headers) {
        if (headers != null) {
            for (Header entry : headers.getEntries()) {
                for (NottableString value : entry.getValues()) {
                    frame.headers().add(entry.getName().getValue().toLowerCase(Locale.ROOT), value.getValue());
                }
            }
        }
    }

    private void scheduleStreamMessages(
        List<GrpcStreamMessage> messages, int index, GrpcStreamResponse action,
        Descriptors.MethodDescriptor methodDescriptor, HttpRequest request,
        boolean streamBreakpointsActive, String streamId, String reqMethod, String reqPath,
        boolean useWsDispatch, String breakpointClientId, String streamBreakpointId
    ) {
        if (streamState.get() == StreamState.COMPLETED) {
            // the deadline already wrote the terminal trailer -- no message may follow it
            return;
        }
        if (index >= messages.size() || !ctx.channel().isActive()) {
            finishGrpcStream(action, streamBreakpointsActive, streamId);
            return;
        }

        GrpcStreamMessage message = messages.get(index);
        Delay delay = message.getDelay();
        long delayMillis = (delay != null) ? delay.sampleValueMillis() : 0;

        Runnable writeAndContinue = () -> {
            if (!ctx.channel().isActive()) {
                return;
            }
            try {
                byte[] frameBytes = GrpcStreamMessageEncoder.encode(message, methodDescriptor, descriptorStore);

                if (!streamBreakpointsActive) {
                    // Default-off fast path: write immediately
                    writeH3GrpcFrame(frameBytes, messages, index, action, methodDescriptor, request,
                        streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                    return;
                }

                // WS-callback dispatch (clientId is always present — required since 7b)
                final java.util.concurrent.CompletableFuture<StreamFrameDecision> decisionFuture;
                int seq = StreamFrameBreakpointRegistry.getInstance().nextSequenceNumber(streamId);
                java.util.concurrent.CompletableFuture<StreamFrameDecision> wsFuture =
                    org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().dispatchFrame(
                        breakpointClientId, streamBreakpointId, streamId, seq, PausedStreamFrame.Direction.OUTBOUND,
                        org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM,
                        frameBytes, reqMethod, reqPath, null, webSocketClientRegistry, configuration, mockServerLogger);
                if (wsFuture != null) {
                    decisionFuture = wsFuture;
                } else {
                    writeH3GrpcFrame(frameBytes, messages, index, action, methodDescriptor, request,
                        streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                    return;
                }

                // Frame is parked. Chain the decision callback onto the QUIC stream's event loop.
                final byte[] capturedFrameBytes = frameBytes;
                decisionFuture.thenAccept(decision ->
                    ctx.channel().eventLoop().execute(() -> {
                        if (!ctx.channel().isActive()) {
                            scheduleStreamMessages(messages, index + 1, action, methodDescriptor, request,
                                streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                            return;
                        }
                        switch (decision.getAction()) {
                            case CONTINUE -> writeH3GrpcFrame(capturedFrameBytes, messages, index,
                                action, methodDescriptor, request, streamBreakpointsActive, streamId, reqMethod, reqPath,
                                useWsDispatch, breakpointClientId, streamBreakpointId);
                            case MODIFY -> writeH3GrpcFrame(decision.getReplacementBody(), messages, index,
                                action, methodDescriptor, request, streamBreakpointsActive, streamId, reqMethod, reqPath,
                                useWsDispatch, breakpointClientId, streamBreakpointId);
                            case DROP ->
                                // Skip this frame -- proceed to next message
                                scheduleStreamMessages(messages, index + 1, action, methodDescriptor, request,
                                    streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                            case INJECT -> {
                                // Write original frame, then inject an extra frame, then proceed
                                DefaultHttp3DataFrame originalData = new DefaultHttp3DataFrame(
                                    Unpooled.wrappedBuffer(capturedFrameBytes));
                                ctx.writeAndFlush(originalData).addListener(future -> {
                                    if (ctx.channel().isActive()) {
                                        DefaultHttp3DataFrame injectedData = new DefaultHttp3DataFrame(
                                            Unpooled.wrappedBuffer(decision.getInjectedBody()));
                                        ctx.writeAndFlush(injectedData).addListener(f2 ->
                                            scheduleStreamMessages(messages, index + 1, action, methodDescriptor,
                                                request, streamBreakpointsActive, streamId, reqMethod, reqPath,
                                                useWsDispatch, breakpointClientId, streamBreakpointId));
                                    } else {
                                        scheduleStreamMessages(messages, index + 1, action, methodDescriptor,
                                            request, streamBreakpointsActive, streamId, reqMethod, reqPath,
                                            useWsDispatch, breakpointClientId, streamBreakpointId);
                                    }
                                });
                            }
                            case CLOSE -> {
                                // End the stream: evict remaining frames and send trailers
                                StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                                finishGrpcStream(action, false, null);
                            }
                        }
                    })
                ).exceptionally(ex -> {
                    if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.DEBUG)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setMessageFormat("stream frame decision callback failed for H3 gRPC stream{}:{}")
                                .setArguments(streamId, ex.getMessage())
                        );
                    }
                    return null;
                });
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setHttpRequest(request)
                            .setMessageFormat("exception sending gRPC stream message {} over HTTP/3 for request:{}")
                            .setArguments(index + 1, request)
                            .setThrowable(e)
                    );
                }
                finishGrpcStream(action, streamBreakpointsActive, streamId);
            }
        };

        if (delayMillis > 0) {
            ctx.executor().schedule(writeAndContinue, delayMillis, TimeUnit.MILLISECONDS);
        } else {
            writeAndContinue.run();
        }
    }

    /**
     * Writes a gRPC frame (byte[]) as an HTTP/3 DATA frame to the QUIC stream channel
     * and chains to the next message on success. Shared between the default-off fast
     * path and the breakpoint resume path.
     */
    private void writeH3GrpcFrame(byte[] frameBytes, List<GrpcStreamMessage> messages, int index,
                                  GrpcStreamResponse action, Descriptors.MethodDescriptor methodDescriptor,
                                  HttpRequest request, boolean streamBreakpointsActive, String streamId,
                                  String reqMethod, String reqPath, boolean useWsDispatch, String breakpointClientId,
                                  String streamBreakpointId) {
        if (streamState.get() == StreamState.COMPLETED) {
            // a per-message delay or breakpoint resume elapsed after the deadline fired
            return;
        }
        DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(frameBytes));
        ctx.writeAndFlush(dataFrame).addListener(future -> {
            if (future.isSuccess()) {
                scheduleStreamMessages(messages, index + 1, action, methodDescriptor, request,
                    streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
            } else {
                finishGrpcStream(action, streamBreakpointsActive, streamId);
            }
        });
    }

    private void finishGrpcStream(GrpcStreamResponse action, boolean streamBreakpointsActive, String streamId) {
        if (streamBreakpointsActive && streamId != null) {
            StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
        }
        // claim the stream: if the deadline already terminated it, do not write a second trailer
        if (streamState.getAndSet(StreamState.COMPLETED) == StreamState.COMPLETED) {
            return;
        }
        completed.set(true);
        if (deadlineFuture != null) {
            deadlineFuture.cancel(false);
            deadlineFuture = null;
        }
        if (!ctx.channel().isActive()) {
            return;
        }
        GrpcStatusMapper.GrpcStatusCode statusCode = GrpcStatusMapper.GrpcStatusCode.OK;
        if (action.getStatusName() != null && !action.getStatusName().isEmpty()) {
            statusCode = GrpcStatusMapper.fromName(action.getStatusName());
        }
        String grpcMessage = (action.getStatusMessage() != null && !action.getStatusMessage().isEmpty())
            ? action.getStatusMessage() : null;

        DefaultHttp3HeadersFrame trailers = GrpcHttp3Adapter.buildTrailingHeadersFrame(
            String.valueOf(statusCode.getCode()), grpcMessage
        );
        ctx.writeAndFlush(trailers).addListener(future -> {
            if (Boolean.TRUE.equals(action.getCloseConnection())) {
                ctx.close();
            } else if (ctx.channel() instanceof QuicStreamChannel) {
                ((QuicStreamChannel) ctx.channel()).shutdownOutput();
            }
        });
    }
}

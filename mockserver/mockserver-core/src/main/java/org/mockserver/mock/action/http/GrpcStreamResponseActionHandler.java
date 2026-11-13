package org.mockserver.mock.action.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.GrpcStreamDeadline;
import org.mockserver.grpc.GrpcStreamMessageEncoder;
import org.mockserver.grpc.GrpcWebTranslator;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.Http2StreamIds;
import org.mockserver.mock.breakpoint.PausedStreamFrame;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.mock.breakpoint.StreamFrameDecision;
import org.mockserver.model.Delay;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.ConnectionClosing;
import org.slf4j.event.Level;

import java.util.Base64;
import java.util.List;

import static org.mockserver.exception.ExceptionHandling.causeDescription;
import static org.mockserver.exception.ExceptionHandling.clientGoneException;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;

public class GrpcStreamResponseActionHandler {

    /**
     * Set on a gRPC-Web call by {@code GrpcToHttpRequestHandler} when it translates it to gRPC.
     */
    private static final String GRPC_WEB_CONTENT_TYPE_HEADER = "x-grpc-web-content-type";

    private final MockServerLogger mockServerLogger;
    private final Scheduler scheduler;
    private final GrpcProtoDescriptorStore descriptorStore;
    private final Configuration configuration;
    private final WebSocketClientRegistry webSocketClientRegistry;
    private final StreamTemplateRenderer templateRenderer;

    public GrpcStreamResponseActionHandler(MockServerLogger mockServerLogger, Scheduler scheduler, GrpcProtoDescriptorStore descriptorStore, Configuration configuration, WebSocketClientRegistry webSocketClientRegistry) {
        this.mockServerLogger = mockServerLogger;
        this.scheduler = scheduler;
        this.descriptorStore = descriptorStore;
        this.configuration = configuration;
        this.webSocketClientRegistry = webSocketClientRegistry;
        this.templateRenderer = new StreamTemplateRenderer(mockServerLogger, configuration);
    }

    public void handle(GrpcStreamResponse grpcStreamResponse, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request) {
        handle(grpcStreamResponse, ctx, request, () -> {
        });
    }

    /**
     * Writes the response straight to {@code ctx}, running {@code responseEnded} once its trailers have been
     * written, unless the connection has closed first.
     */
    public void handle(GrpcStreamResponse grpcStreamResponse, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request, Runnable responseEnded) {
        String serviceName = request.getFirstHeader("x-grpc-service");
        String methodName = request.getFirstHeader("x-grpc-method");

        com.google.protobuf.Descriptors.MethodDescriptor methodDescriptor = null;
        if (serviceName != null && !serviceName.isEmpty() && methodName != null && !methodName.isEmpty()) {
            methodDescriptor = descriptorStore.getMethod(serviceName, methodName);
        }

        DefaultHttpResponse initialResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK
        );

        String grpcWebContentType = grpcWebContentType(request);
        initialResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, grpcWebContentType != null
            ? GrpcWebTranslator.responseContentType(grpcWebContentType)
            : GrpcStatusMapper.GRPC_CONTENT_TYPE);
        initialResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");

        if (grpcStreamResponse.getHeaders() != null) {
            grpcStreamResponse.getHeaders().getEntries().forEach(header ->
                header.getValues().forEach(value ->
                    initialResponse.headers().add(header.getName().getValue(), value.getValue())
                )
            );
        }

        // Reply on the HTTP/2 stream the request arrived on. This handler writes raw Netty objects
        // straight to the channel, bypassing MockServerHttpResponseToFullHttpResponse, so nothing
        // else stamps the stream id. Without it HttpToHttp2ConnectionHandler.getStreamId falls back
        // to connection().local().incrementAndGetNextStreamId() and the whole stream -- initial
        // HEADERS, every DATA frame and the trailers -- is written to a FRESH server-initiated
        // stream that the client is not reading, so a real gRPC client receives NOTHING and hangs
        // until its deadline (GitHub issue #2419). Netty's adapter latches this id from the initial
        // HttpMessage and reuses it for the subsequent HttpContent frames, so setting it here covers
        // the whole stream. Null on HTTP/1.1, where the header is simply not set.
        //
        // Stamped AFTER the user-supplied headers above, and via Http2StreamIds (which replaces
        // rather than appends): a user-supplied x-http2-stream-id would otherwise overwrite the
        // correct id with a foreign one, which triggers a PROTOCOL_ERROR / GOAWAY that hangs the
        // client just as badly as the missing-header case.
        Http2StreamIds.stampFromRequest(initialResponse, request);

        ctx.writeAndFlush(initialResponse);

        // Enforce the client's grpc-timeout for the WHOLE streaming RPC. If the deadline elapses
        // mid-stream, terminate with a DEADLINE_EXCEEDED trailer and stop emitting messages -- a
        // streaming expectation whose per-message delays outlast the client's deadline previously
        // kept writing to a stream the client had already given up on.
        final GrpcStreamDeadline deadline = new GrpcStreamDeadline();
        deadline.schedule(ctx, request, () -> writeDeadlineExceededTrailer(ctx, deadline, request, responseEnded));
        // This deadline is local to the invocation, so it is not registered in GrpcPendingRequests
        // and channelInactive's cancelAllDeadlines does not reach it. Without this a long
        // grpc-timeout (say 8H) would keep its task queued for the full duration after the channel
        // died. The HTTP/3 and bidi paths already cancel on close; this was the gap.
        ctx.channel().closeFuture().addListener(closeFuture -> deadline.cancel());

        // Determine if stream-frame breakpoints are active
        final org.mockserver.mock.breakpoint.BreakpointMatcher streamBreakpointMatcher = org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().findMatch(request, org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM);
        final boolean streamBreakpointsActive = streamBreakpointMatcher != null;
        final String streamId;
        final String reqMethod;
        final String reqPath;
        final boolean useWsDispatch;
        final String breakpointClientId;
        if (streamBreakpointsActive) {
            streamId = (request.getLogCorrelationId() != null
                ? request.getLogCorrelationId() : org.mockserver.uuid.UUIDService.getNonSecureUUID()) + "-grpc-stream";
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

        List<GrpcStreamMessage> messages = grpcStreamResponse.getMessages();
        if (messages != null && !messages.isEmpty()) {
            scheduleMessages(deadline, responseEnded, messages, 0, ctx, grpcStreamResponse, request, methodDescriptor, streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId,
                streamBreakpointMatcher != null ? streamBreakpointMatcher.getId() : null);
        } else {
            finishStream(deadline, responseEnded, ctx, grpcStreamResponse, request, streamBreakpointsActive, streamId);
        }
    }

    private void scheduleMessages(GrpcStreamDeadline deadline, Runnable responseEnded, List<GrpcStreamMessage> messages, int index, ChannelHandlerContext ctx,
                                   GrpcStreamResponse grpcStreamResponse, org.mockserver.model.HttpRequest request,
                                   com.google.protobuf.Descriptors.MethodDescriptor methodDescriptor,
                                   boolean streamBreakpointsActive, String streamId, String reqMethod, String reqPath,
                                   boolean useWsDispatch, String breakpointClientId, String streamBreakpointId) {
        if (deadline.isTerminated()) {
            // the deadline already wrote the terminal trailer -- emitting anything now would put a
            // message after end-of-stream
            return;
        }
        if (index >= messages.size() || !ctx.channel().isActive()) {
            finishStream(deadline, responseEnded, ctx, grpcStreamResponse, request, streamBreakpointsActive, streamId);
            return;
        }

        GrpcStreamMessage message = messages.get(index);
        Delay delay = message.getDelay();

        Runnable writeMessage = () -> {
            try {
                if (!ctx.channel().isActive()) {
                    return;
                }
                byte[] frameBytes = encodeMessage(message, methodDescriptor, request);

                if (!streamBreakpointsActive) {
                    // Default-off fast path: write immediately
                    writeGrpcFrame(deadline, responseEnded, frameBytes, ctx, request, messages, index, grpcStreamResponse, methodDescriptor,
                        streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                    return;
                }

                // --- Stream-frame breakpoint path ---
                // frameBytes is already a byte[] copy (from encodeMessage) -- no ByteBuf refcount concern
                final java.util.concurrent.CompletableFuture<StreamFrameDecision> decisionFuture;

                // WS-callback dispatch (clientId is always present — required since 7b)
                int seq = StreamFrameBreakpointRegistry.getInstance().nextSequenceNumber(streamId);
                java.util.concurrent.CompletableFuture<StreamFrameDecision> wsFuture =
                    org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().dispatchFrame(
                        breakpointClientId, streamBreakpointId, streamId, seq,
                        PausedStreamFrame.Direction.OUTBOUND,
                        org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM,
                        frameBytes, reqMethod, reqPath, null,
                        webSocketClientRegistry,
                        configuration, mockServerLogger
                    );
                if (wsFuture == null) {
                    // Cap reached or client not connected -- write immediately
                    writeGrpcFrame(deadline, responseEnded, frameBytes, ctx, request, messages, index, grpcStreamResponse, methodDescriptor,
                        streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                    return;
                }
                decisionFuture = wsFuture;

                // Frame is parked. Chain the decision callback onto the channel's event loop.
                final byte[] capturedFrameBytes = frameBytes;
                decisionFuture.thenAccept(decision ->
                    ctx.channel().eventLoop().execute(() -> {
                        if (!ctx.channel().isActive()) {
                            scheduleMessages(deadline, responseEnded, messages, index + 1, ctx, grpcStreamResponse, request, methodDescriptor,
                                streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                            return;
                        }
                        switch (decision.getAction()) {
                            case CONTINUE -> writeGrpcFrame(deadline, responseEnded, capturedFrameBytes, ctx, request, messages, index,
                                grpcStreamResponse, methodDescriptor, streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                            case MODIFY -> writeGrpcFrame(deadline, responseEnded, decision.getReplacementBody(), ctx, request, messages, index,
                                grpcStreamResponse, methodDescriptor, streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                            case DROP ->
                                // Skip this frame -- proceed to next message
                                scheduleMessages(deadline, responseEnded, messages, index + 1, ctx, grpcStreamResponse, request, methodDescriptor,
                                    streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                            case INJECT -> {
                                // Write original frame, then inject an extra frame, then proceed
                                DefaultHttpContent originalContent = new DefaultHttpContent(
                                    Unpooled.wrappedBuffer(forClient(capturedFrameBytes, request)));
                                ctx.writeAndFlush(originalContent).addListener(future -> {
                                    if (ctx.channel().isActive()) {
                                        DefaultHttpContent injectedContent = new DefaultHttpContent(
                                            Unpooled.wrappedBuffer(forClient(decision.getInjectedBody(), request)));
                                        ctx.writeAndFlush(injectedContent).addListener(f2 ->
                                            scheduleMessages(deadline, responseEnded, messages, index + 1, ctx, grpcStreamResponse, request,
                                                methodDescriptor, streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId));
                                    } else {
                                        scheduleMessages(deadline, responseEnded, messages, index + 1, ctx, grpcStreamResponse, request,
                                            methodDescriptor, streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                                    }
                                });
                            }
                            case CLOSE -> {
                                // End the stream: evict remaining frames and send trailers
                                StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                                finishStream(deadline, responseEnded, ctx, grpcStreamResponse, request, false, null);
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
                                .setMessageFormat("stream frame decision callback failed for gRPC stream{}:{}")
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
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception sending gRPC stream message {} for request:{}")
                            .setArguments(index + 1, request)
                            .setThrowable(e)
                    );
                }
                finishStream(deadline, responseEnded, ctx, grpcStreamResponse, request, streamBreakpointsActive, streamId);
            }
        };

        if (delay != null) {
            scheduler.schedule(writeMessage, false, delay);
        } else {
            writeMessage.run();
        }
    }

    /**
     * Writes a gRPC frame (byte[]) to the channel and chains to the next message on success.
     * Shared between the default-off fast path and the breakpoint resume path.
     */
    private void writeGrpcFrame(GrpcStreamDeadline deadline, Runnable responseEnded, byte[] frameBytes, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request,
                                List<GrpcStreamMessage> messages, int index, GrpcStreamResponse grpcStreamResponse,
                                com.google.protobuf.Descriptors.MethodDescriptor methodDescriptor,
                                boolean streamBreakpointsActive, String streamId, String reqMethod, String reqPath,
                                boolean useWsDispatch, String breakpointClientId, String streamBreakpointId) {
        if (deadline.isTerminated()) {
            // a per-message delay or breakpoint resume elapsed after the deadline fired
            return;
        }
        DefaultHttpContent content = new DefaultHttpContent(Unpooled.wrappedBuffer(forClient(frameBytes, request)));
        ctx.writeAndFlush(content).addListener(future -> {
            if (future.isSuccess()) {
                if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(EXPECTATION_RESPONSE)
                            .setLogLevel(Level.DEBUG)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("sent gRPC stream message {} of {} for request:{}")
                            .setArguments(index + 1, messages.size(), request)
                    );
                }
                scheduleMessages(deadline, responseEnded, messages, index + 1, ctx, grpcStreamResponse, request, methodDescriptor,
                    streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
            } else if (clientGoneException(future.cause())) {
                // a client that has gone is an ordinary end of the response
                if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.DEBUG)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("client left before gRPC stream message {} was sent:{}for request:{}")
                            .setArguments(index + 1, causeDescription(future.cause()), request)
                    );
                }
                finishStream(deadline, responseEnded, ctx, grpcStreamResponse, request, streamBreakpointsActive, streamId);
            } else {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("async write failure for gRPC stream message {} for request:{}")
                            .setArguments(index + 1, request)
                            .setThrowable(future.cause())
                    );
                }
                finishStream(deadline, responseEnded, ctx, grpcStreamResponse, request, streamBreakpointsActive, streamId);
            }
        });
    }

    /**
     * Terminates an in-progress streaming RPC because the client's deadline elapsed.
     * <p>
     * Only ever reached having already won {@link GrpcStreamDeadline#tryTerminate()}, so this
     * cannot race the normal {@code finishStream} trailer.
     */
    private void writeDeadlineExceededTrailer(ChannelHandlerContext ctx, GrpcStreamDeadline deadline,
                                              org.mockserver.model.HttpRequest request, Runnable responseEnded) {
        deadline.cancel();
        if (!ctx.channel().isActive()) {
            return;
        }
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat("gRPC deadline elapsed mid-stream for request:{} - terminating with DEADLINE_EXCEEDED")
                    .setArguments(request)
            );
        }
        LastHttpContent trailers = terminalFrame(String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode()),
            deadline.deadlineExceededMessage(), request);
        ctx.writeAndFlush(trailers).addListener(future -> responseEnded.run());
    }

    private byte[] encodeMessage(GrpcStreamMessage message, com.google.protobuf.Descriptors.MethodDescriptor methodDescriptor, org.mockserver.model.HttpRequest request) {
        // Opt-in per-message templating: when the message has a templateType, render its JSON against the
        // triggering request (full request/template context — request fields, scenario state, faker) before
        // encoding. When templateType is null the raw JSON is encoded unchanged (static, byte-for-byte).
        String json = message.getTemplateType() != null
            ? templateRenderer.render(message.getTemplateType(), message.getJson(), request)
            : message.getJson();
        // Delegate to the transport-neutral encoder so HTTP/2 and HTTP/3 server-streaming
        // produce byte-identical gRPC frames.
        return GrpcStreamMessageEncoder.encode(json, methodDescriptor, descriptorStore);
    }

    private void finishStream(GrpcStreamDeadline deadline, Runnable responseEnded, ChannelHandlerContext ctx, GrpcStreamResponse grpcStreamResponse,
                              org.mockserver.model.HttpRequest request, boolean streamBreakpointsActive, String streamId) {
        if (streamBreakpointsActive && streamId != null) {
            StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
        }
        // Claim the stream, so whichever of normal completion and the deadline gets here first wins.
        //
        // On the synchronous emission paths (scheduleMessages, writeGrpcFrame) this claim is
        // defence in depth: those are already fronted by a deadline.isTerminated() check, so
        // degrading it there alone changes no observable behaviour in unit scope.
        //
        // On the two ASYNCHRONOUS paths it is the live guard, and the only thing preventing a
        // second terminal trailer after DEADLINE_EXCEEDED:
        //   - the breakpoint CLOSE decision, resolved from decisionFuture.thenAccept, which can
        //     land arbitrarily long after the isTerminated check (a breakpoint is human-driven);
        //   - the writeMessage catch block, reached after scheduler.schedule(...) when a render or
        //     encode throws, by which time the deadline may already have fired.
        // Neither is reachable in a single-threaded unit harness, which is why the primitive's
        // single-shot property is pinned directly by GrpcStreamDeadlineContractTest rather than
        // through a handler test that would not discriminate.
        if (!deadline.tryTerminate()) {
            return;
        }
        deadline.cancel();
        if (ctx.channel().isActive()) {
            GrpcStatusMapper.GrpcStatusCode statusCode = GrpcStatusMapper.GrpcStatusCode.OK;
            if (grpcStreamResponse.getStatusName() != null && !grpcStreamResponse.getStatusName().isEmpty()) {
                statusCode = GrpcStatusMapper.fromName(grpcStreamResponse.getStatusName());
            }

            LastHttpContent trailers = terminalFrame(String.valueOf(statusCode.getCode()), grpcStreamResponse.getStatusMessage(), request);

            if (Boolean.TRUE.equals(grpcStreamResponse.getCloseConnection()) && !(ctx.channel() instanceof Http2StreamChannel)) {
                // the close follows the trailers' write, possibly off the event loop
                ConnectionClosing.mark(ctx.channel());
            }
            ctx.writeAndFlush(trailers).addListener(future -> {
                responseEnded.run();
                if (grpcStreamResponse.getCloseConnection() != null && grpcStreamResponse.getCloseConnection()) {
                    ctx.close();
                }
            });
        }
    }

    /**
     * The gRPC-Web content-type the call was made with, or {@code null} for a gRPC call.
     */
    private static String grpcWebContentType(org.mockserver.model.HttpRequest request) {
        String grpcWebContentType = request.getFirstHeader(GRPC_WEB_CONTENT_TYPE_HEADER);
        return grpcWebContentType.isEmpty() ? null : grpcWebContentType;
    }

    /**
     * The bytes to write for {@code frames}: as they are, or base64-encoded for a gRPC-Web text call.
     */
    private static byte[] forClient(byte[] frames, org.mockserver.model.HttpRequest request) {
        return GrpcWebTranslator.isGrpcWebTextContentType(grpcWebContentType(request))
            ? Base64.getEncoder().encode(frames)
            : frames;
    }

    /**
     * The end of the response: {@code grpc-status} and {@code grpc-message} as HTTP trailers, or,
     * for gRPC-Web, whose clients cannot read HTTP trailers, as a trailer frame in the body.
     */
    private static LastHttpContent terminalFrame(String grpcStatus, String grpcMessage, org.mockserver.model.HttpRequest request) {
        String grpcWebContentType = grpcWebContentType(request);
        if (grpcWebContentType != null) {
            byte[] trailerFrame = GrpcWebTranslator.buildTrailerFrame(grpcStatus, grpcMessage);
            return new DefaultLastHttpContent(Unpooled.wrappedBuffer(forClient(trailerFrame, request)));
        }
        DefaultLastHttpContent trailers = new DefaultLastHttpContent();
        trailers.trailingHeaders().set(GrpcStatusMapper.GRPC_STATUS_HEADER, grpcStatus);
        if (grpcMessage != null && !grpcMessage.isEmpty()) {
            trailers.trailingHeaders().set(GrpcStatusMapper.GRPC_MESSAGE_HEADER, GrpcStatusMapper.percentEncodeMessage(grpcMessage));
        }
        return trailers;
    }
}

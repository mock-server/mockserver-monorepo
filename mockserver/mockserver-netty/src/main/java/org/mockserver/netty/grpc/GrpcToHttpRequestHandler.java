package org.mockserver.netty.grpc;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcDerivedHeaders;
import org.mockserver.grpc.GrpcException;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcHealthCheckHandler;
import org.mockserver.grpc.GrpcHealthRegistry;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcServerReflectionHandler;
import org.mockserver.grpc.GrpcResponseStatusResolver;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.GrpcTimeout;
import org.mockserver.grpc.GrpcWebTranslator;
import org.mockserver.grpc.ServingStatus;
import org.mockserver.mock.action.http.GrpcChaosDecision;
import org.mockserver.mock.action.http.GrpcChaosRegistry;
import org.mockserver.mock.action.http.HttpQuotaRegistry;
import org.mockserver.model.GrpcChaosProfile;
import com.google.protobuf.Descriptors;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.DataPlaneAuthenticationGate;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.WWW_AUTHENTICATE;

@ChannelHandler.Sharable
public class GrpcToHttpRequestHandler extends SimpleChannelInboundHandler<HttpRequest> {

    private final MockServerLogger mockServerLogger;
    private final GrpcProtoDescriptorStore descriptorStore;
    /** Live configuration, when available; null falls back to the static property store. */
    private Configuration configuration;
    private final GrpcHealthCheckHandler healthCheckHandler;
    private final GrpcServerReflectionHandler reflectionHandler;
    private final GrpcChaosRegistry grpcChaosRegistry;
    private final HttpQuotaRegistry quotaRegistry;

    public GrpcToHttpRequestHandler(MockServerLogger mockServerLogger, GrpcProtoDescriptorStore descriptorStore) {
        this(mockServerLogger, descriptorStore, new GrpcHealthCheckHandler(GrpcHealthRegistry.getInstance()),
            GrpcChaosRegistry.getInstance(), HttpQuotaRegistry.getInstance());
    }

    /**
     * Preferred constructor: carries the live {@link Configuration} so {@code maxGrpcMessageSize}
     * set on a {@code Configuration} instance (or via the DTO / {@code PUT /mockserver/config})
     * actually reaches enforcement, rather than only the static property store being consulted.
     */
    public GrpcToHttpRequestHandler(Configuration configuration, MockServerLogger mockServerLogger, GrpcProtoDescriptorStore descriptorStore) {
        this(mockServerLogger, descriptorStore, new GrpcHealthCheckHandler(GrpcHealthRegistry.getInstance()),
            GrpcChaosRegistry.getInstance(), HttpQuotaRegistry.getInstance());
        this.configuration = configuration;
    }

    public GrpcToHttpRequestHandler(MockServerLogger mockServerLogger, GrpcProtoDescriptorStore descriptorStore, GrpcHealthCheckHandler healthCheckHandler) {
        this(mockServerLogger, descriptorStore, healthCheckHandler,
            GrpcChaosRegistry.getInstance(), HttpQuotaRegistry.getInstance());
    }

    GrpcToHttpRequestHandler(Configuration configuration, MockServerLogger mockServerLogger, GrpcProtoDescriptorStore descriptorStore,
                             GrpcHealthCheckHandler healthCheckHandler,
                             GrpcChaosRegistry grpcChaosRegistry, HttpQuotaRegistry quotaRegistry) {
        this(mockServerLogger, descriptorStore, healthCheckHandler, grpcChaosRegistry, quotaRegistry);
        this.configuration = configuration;
    }

    public GrpcToHttpRequestHandler(MockServerLogger mockServerLogger, GrpcProtoDescriptorStore descriptorStore,
                                    GrpcHealthCheckHandler healthCheckHandler,
                                    GrpcChaosRegistry grpcChaosRegistry, HttpQuotaRegistry quotaRegistry) {
        this.mockServerLogger = mockServerLogger;
        this.descriptorStore = descriptorStore;
        this.healthCheckHandler = healthCheckHandler;
        this.reflectionHandler = new GrpcServerReflectionHandler(descriptorStore);
        this.grpcChaosRegistry = grpcChaosRegistry;
        this.quotaRegistry = quotaRegistry;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // a scheduled deadline must not outlive the channel that scheduled it
        GrpcPendingRequests pendingRequests = GrpcPendingRequests.existingForChannel(ctx.channel());
        if (pendingRequests != null) {
            pendingRequests.cancelAllDeadlines();
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {
        // Strip MockServer's internal deadline marker from anything inbound. Only the scheduled
        // deadline task may set it; a client (or an upstream server on the forward-proxy path)
        // supplying it would otherwise take the deadline early-return in
        // GrpcToHttpResponseHandler.encode and skip protobuf conversion entirely.
        if (!request.getFirstHeader(GrpcResponseStatusResolver.GRPC_DEADLINE_RESPONSE_MARKER).isEmpty()) {
            request.removeHeader(GrpcResponseStatusResolver.GRPC_DEADLINE_RESPONSE_MARKER);
        }
        String contentType = request.getFirstHeader("content-type");
        // The compressed flag on a frame says THAT a message is compressed, not HOW -- that is
        // grpc-encoding. Read it so an unsupported encoding is reported as UNIMPLEMENTED (with
        // grpc-accept-encoding telling the client what to retry with) rather than failing inside
        // gzip and surfacing as an opaque INTERNAL.
        String grpcEncoding = request.getFirstHeader(GrpcStatusMapper.GRPC_ENCODING_HEADER);
        // Translate gRPC-Web requests to standard gRPC before processing.
        // Track the original gRPC-Web content-type so direct responses (health check,
        // reflection, chaos) can be tagged for gRPC-Web re-framing by the response handler.
        String grpcWebContentType = null;
        if (GrpcWebTranslator.isGrpcWebContentType(contentType)) {
            grpcWebContentType = contentType;
            request = translateGrpcWebRequest(request, contentType);
            contentType = request.getFirstHeader("content-type");
        }
        // Handle gRPC health check without requiring a descriptor
        if (GrpcStatusMapper.isGrpcContentType(contentType) && healthCheckHandler != null) {
            String path = request.getPath() != null ? request.getPath().getValue() : "";
            if (healthCheckHandler.isHealthCheckRequest(path)) {
                String serviceName = healthCheckHandler.decodeServiceName(request.getBodyAsRawBytes());
                if (!healthCheckHandler.isRegistered(serviceName)) {
                    // grpc.health.v1.Health/Check MUST fail the RPC with NOT_FOUND for a service
                    // the server does not know about. Returning SERVING here would report a
                    // mistyped service name as healthy.
                    org.mockserver.model.HttpResponse unknownServiceResponse = org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                        .withTrailer(GrpcStatusMapper.GRPC_STATUS_HEADER,
                            String.valueOf(GrpcStatusMapper.GrpcStatusCode.NOT_FOUND.getCode()))
                        .withTrailer(GrpcStatusMapper.GRPC_MESSAGE_HEADER,
                            GrpcStatusMapper.percentEncodeMessage("unknown service " + serviceName));
                    tagGrpcWebResponse(unknownServiceResponse, request, grpcWebContentType);
                    ctx.writeAndFlush(unknownServiceResponse);
                    return;
                }
                ServingStatus status = healthCheckHandler.getStatus(serviceName);
                byte[] responseBody = healthCheckHandler.encodeResponse(status);
                org.mockserver.model.HttpResponse healthResponse = org.mockserver.model.HttpResponse.response()
                    .withStatusCode(200)
                    .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                    .withTrailer(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
                    .withBody(responseBody);
                tagGrpcWebResponse(healthResponse, request, grpcWebContentType);
                ctx.writeAndFlush(healthResponse);
                return;
            }
        }
        // Health stays open, like /mockserver/status and /ready. What follows answers from the loaded schemas or
        // mock state, some of it before HttpRequestHandler's data-plane gate would run, so it is gated here.
        if (GrpcStatusMapper.isGrpcContentType(contentType) && refusedWithoutDataPlaneCredentials(ctx, request, grpcWebContentType)) {
            return;
        }
        // Handle gRPC Server Reflection without requiring user-defined expectations
        if (GrpcStatusMapper.isGrpcContentType(contentType) && reflectionHandler != null && descriptorStore.hasServices()) {
            String path = request.getPath() != null ? request.getPath().getValue() : "";
            if (reflectionHandler.isReflectionRequest(path)) {
                try {
                    byte[] responseBody = reflectionHandler.handleReflectionRequest(request.getBodyAsRawBytes());
                    org.mockserver.model.HttpResponse reflectionResponse = org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                        .withTrailer(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
                        .withBody(responseBody);
                    tagGrpcWebResponse(reflectionResponse, request, grpcWebContentType);
                    ctx.writeAndFlush(reflectionResponse);
                } catch (Exception e) {
                    if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.WARN)
                                .setMessageFormat("gRPC reflection request error:{}:{}")
                                .setArguments(request.getPath(), e.getMessage())
                        );
                    }
                    org.mockserver.model.HttpResponse errorResponse = org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                        .withTrailer(GrpcStatusMapper.GRPC_STATUS_HEADER,
                            String.valueOf(GrpcStatusMapper.GrpcStatusCode.INTERNAL.getCode()))
                        .withTrailer(GrpcStatusMapper.GRPC_MESSAGE_HEADER,
                            GrpcStatusMapper.percentEncodeMessage("reflection request failed: " + e.getMessage()));
                    tagGrpcWebResponse(errorResponse, request, grpcWebContentType);
                    ctx.writeAndFlush(errorResponse);
                }
                return;
            }
        }
        // gRPC chaos fault injection: probabilistically return a gRPC error status
        // before normal request handling. Only on the error-injection path is latency applied;
        // pass-through latency is a future addition.
        if (GrpcStatusMapper.isGrpcContentType(contentType)) {
            String chaosPath = request.getPath() != null ? request.getPath().getValue() : "";
            String[] chaosParts = parseGrpcPath(chaosPath);
            String chaosServiceName = chaosParts[0];
            GrpcChaosProfile chaosProfile = grpcChaosRegistry.get(chaosServiceName);
            if (chaosProfile != null && chaosProfile.hasAnyFault()) {
                int matchCount = grpcChaosRegistry.incrementMatchCount(chaosServiceName);

                // abortAfterMessages: decode the body to count client-streaming messages
                // and inject ABORTED when the count meets the threshold
                Integer abortThreshold = chaosProfile.getAbortAfterMessages();
                if (abortThreshold != null && chaosProfile.countWindowEligible(matchCount)) {
                    byte[] bodyBytes = request.getBodyAsRawBytes();
                    int messageCount = 0;
                    if (bodyBytes != null && bodyBytes.length > 0) {
                        try {
                            messageCount = GrpcFrameCodec.decode(bodyBytes, grpcEncoding, configuration).size();
                        } catch (Exception ignored) {
                            // body not decodable as gRPC frames; treat as 0 messages
                        }
                    }
                    if (messageCount >= abortThreshold) {
                        org.mockserver.model.HttpResponse abortResponse = buildFaultResponse(
                            chaosProfile,
                            GrpcStatusMapper.GrpcStatusCode.ABORTED,
                            chaosProfile.getErrorMessage() != null ? chaosProfile.getErrorMessage() : "aborted after " + messageCount + " messages"
                        );
                        tagGrpcWebResponse(abortResponse, request, grpcWebContentType);
                        scheduleFaultResponse(ctx, chaosProfile, abortResponse);
                        return;
                    }
                    // under threshold: fall through to evaluate other faults (if any)
                }

                GrpcChaosDecision.GrpcFault fault = GrpcChaosDecision.evaluate(chaosProfile, matchCount, quotaRegistry);
                if (fault != null) {
                    org.mockserver.model.HttpResponse errorResponse = buildFaultResponse(
                        chaosProfile, fault.getStatusCode(),
                        fault.getMessage() != null ? fault.getMessage() : fault.getStatusCode().name()
                    );
                    tagGrpcWebResponse(errorResponse, request, grpcWebContentType);
                    scheduleFaultResponse(ctx, chaosProfile, errorResponse);
                    return;
                }

                // omitGrpcStatus / corruptGrpcStatus as standalone faults
                // (when no error probability/quota is configured but these are set)
                if (chaosProfile.countWindowEligible(matchCount)) {
                    if (Boolean.TRUE.equals(chaosProfile.getOmitGrpcStatus())
                        || Boolean.TRUE.equals(chaosProfile.getCorruptGrpcStatus())
                        || (chaosProfile.getCustomTrailers() != null && !chaosProfile.getCustomTrailers().isEmpty())) {
                        org.mockserver.model.HttpResponse faultResponse = buildFaultResponse(
                            chaosProfile,
                            GrpcStatusMapper.GrpcStatusCode.INTERNAL,
                            chaosProfile.getErrorMessage() != null ? chaosProfile.getErrorMessage() : "chaos fault"
                        );
                        tagGrpcWebResponse(faultResponse, request, grpcWebContentType);
                        scheduleFaultResponse(ctx, chaosProfile, faultResponse);
                        return;
                    }
                }
            }
        }
        if (GrpcStatusMapper.isGrpcContentType(contentType) && descriptorStore.hasServices()) {
            try {
                HttpRequest converted = convertGrpcRequest(ctx, request);
                ctx.fireChannelRead(converted);
            } catch (GrpcException e) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("gRPC request error:{}:{}")
                            .setArguments(request.getPath(), e.getMessage())
                    );
                }
                // The status now travels on the exception rather than being inferred from the
                // message text, so an oversize message reports RESOURCE_EXHAUSTED and an
                // unsupported grpc-encoding reports UNIMPLEMENTED, instead of everything except
                // "unknown gRPC method" collapsing to INTERNAL.
                GrpcStatusMapper.GrpcStatusCode statusCode = e.getMessage() != null && e.getMessage().startsWith("unknown gRPC method")
                    ? GrpcStatusMapper.GrpcStatusCode.UNIMPLEMENTED
                    : e.getStatusCode();
                org.mockserver.model.HttpResponse errorResponse = org.mockserver.model.HttpResponse.response()
                    .withStatusCode(200)
                    .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                    .withHeader(GrpcStatusMapper.GRPC_ACCEPT_ENCODING_HEADER, GrpcFrameCodec.ACCEPT_ENCODING)
                    .withTrailer(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(statusCode.getCode()))
                    .withTrailer(GrpcStatusMapper.GRPC_MESSAGE_HEADER, GrpcStatusMapper.percentEncodeMessage(e.getMessage()));
                tagGrpcWebResponse(errorResponse, request, grpcWebContentType);
                ctx.writeAndFlush(errorResponse);
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("failed to convert gRPC request to JSON:{}:{}")
                            .setArguments(request.getPath(), e.getMessage())
                    );
                }
                org.mockserver.model.HttpResponse errorResponse = org.mockserver.model.HttpResponse.response()
                    .withStatusCode(200)
                    .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                    .withTrailer(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(GrpcStatusMapper.GrpcStatusCode.INTERNAL.getCode()))
                    .withTrailer(GrpcStatusMapper.GRPC_MESSAGE_HEADER, GrpcStatusMapper.percentEncodeMessage("failed to decode gRPC request"));
                tagGrpcWebResponse(errorResponse, request, grpcWebContentType);
                ctx.writeAndFlush(errorResponse);
            }
        } else {
            // A non-gRPC request on this connection (for example a control-plane call on the same
            // port). Discard any HTTP/1.1 record left behind by an abandoned gRPC exchange -- one
            // that never reached GrpcToHttpResponseHandler.encode because the connection was
            // dropped, a breakpoint was never released, or an exception was thrown before the
            // response was written -- so it cannot convert this unrelated response.
            // HTTP/2 records are stream-keyed and so are already unreachable from another stream.
            GrpcPendingRequests existing = GrpcPendingRequests.existingForChannel(ctx.channel());
            if (existing != null) {
                existing.clearWithoutStreamId();
            }
            ctx.fireChannelRead(request);
        }
    }

    /**
     * Refuses a gRPC request without the data-plane credentials {@code dataPlaneAuthenticationRequired} asks for,
     * with the gRPC status ({@code UNAUTHENTICATED}) and {@code WWW-Authenticate} challenge HttpRequestHandler's
     * gate would give it once converted.
     */
    private boolean refusedWithoutDataPlaneCredentials(ChannelHandlerContext ctx, HttpRequest request, String grpcWebContentType) {
        if (configuration == null || !configuration.dataPlaneAuthenticationRequired()) {
            return false;
        }
        org.mockserver.model.HttpResponse unauthorized = DataPlaneAuthenticationGate.unauthorizedResponse(configuration, mockServerLogger, request);
        if (unauthorized == null) {
            return false;
        }
        GrpcResponseStatusResolver.ResolvedStatus status = GrpcResponseStatusResolver.resolve(unauthorized);
        org.mockserver.model.HttpResponse refusal = org.mockserver.model.HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withTrailer(GrpcStatusMapper.GRPC_STATUS_HEADER, status.code())
            .withTrailer(GrpcStatusMapper.GRPC_MESSAGE_HEADER, GrpcStatusMapper.percentEncodeMessage(status.message()));
        String challenge = unauthorized.getFirstHeader(WWW_AUTHENTICATE.toString());
        if (!challenge.isEmpty()) {
            refusal.withHeader(WWW_AUTHENTICATE.toString(), challenge);
        }
        tagGrpcWebResponse(refusal, request, grpcWebContentType);
        ctx.writeAndFlush(refusal);
        return true;
    }

    /**
     * Prepares a response this handler writes DIRECTLY (health check, reflection, chaos faults,
     * decode errors) rather than passing down the pipeline to the matching engine.
     * <p>
     * Two things are needed, and both are easy to forget on a new direct-response path, which is
     * why every such write funnels through here:
     * <ol>
     *   <li><strong>The HTTP/2 stream id.</strong> Responses that go through the matching engine
     *       get this from {@code ResponseWriter.writeResponse}; a direct write does not. Without
     *       it {@code HttpToHttp2ConnectionHandler.getStreamId} falls back to
     *       {@code connection().local().incrementAndGetNextStreamId()} and replies on a FRESH
     *       server-initiated stream, so the client never receives the response on the stream it
     *       asked on -- its call hangs until deadline. Harmless on HTTP/1.1, where the id is null.</li>
     *   <li><strong>The gRPC-Web content-type marker</strong>, so
     *       {@link GrpcToHttpResponseHandler} can re-frame the response as gRPC-Web.</li>
     * </ol>
     */
    private static void tagGrpcWebResponse(org.mockserver.model.HttpResponse response, HttpRequest request, String grpcWebContentType) {
        if (response.getFirstHeader(GrpcStatusMapper.GRPC_ACCEPT_ENCODING_HEADER).isEmpty()) {
            response.withHeader(GrpcStatusMapper.GRPC_ACCEPT_ENCODING_HEADER, GrpcFrameCodec.ACCEPT_ENCODING);
        }
        if (request != null && request.getStreamId() != null) {
            response.withStreamId(request.getStreamId());
        }
        if (grpcWebContentType != null) {
            response.withHeader("x-grpc-web-content-type", grpcWebContentType);
        }
    }

    /**
     * Translates a gRPC-Web request into a standard gRPC request so that
     * the existing gRPC pipeline can process it unchanged.
     * <p>
     * For the {@code -text} variant the body is base64-decoded.
     * The original content-type is preserved in {@code x-grpc-web-content-type}
     * so the response handler can re-frame the response as gRPC-Web.
     */
    private HttpRequest translateGrpcWebRequest(HttpRequest request, String contentType) {
        byte[] body = request.getBodyAsRawBytes();
        byte[] decodedBody = GrpcWebTranslator.decodeRequestBody(body, contentType);
        return request
            .clone()
            .replaceHeader(new org.mockserver.model.Header("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE))
            .withHeader("x-grpc-web-content-type", contentType)
            .withBody(decodedBody != null ? new org.mockserver.model.BinaryBody(decodedBody) : null);
    }

    /**
     * Decodes a gRPC request into a JSON-bodied {@link HttpRequest} for expectation matching, and
     * records the resolved service/method on the channel so
     * {@link GrpcToHttpResponseHandler} can re-frame the matched response as protobuf.
     * <p>
     * The {@code x-grpc-service}/{@code x-grpc-method} headers set here are on the REQUEST only --
     * the matching pipeline does not propagate them onto the matched response, so the channel
     * attribute is what makes conversion fire for a normal mock expectation (issue #2419).
     * <p>
     * Only this (mock-matching) path records the attribute. The health-check, reflection and chaos
     * paths short-circuit earlier in {@link #channelRead0} and write already-framed responses; if
     * they set the attribute those responses would be double-framed.
     */
    private HttpRequest convertGrpcRequest(ChannelHandlerContext ctx, HttpRequest request) {
        String path = request.getPath() != null ? request.getPath().getValue() : "";
        String[] parts = parseGrpcPath(path);
        String serviceName = parts[0];
        String methodName = parts[1];

        Descriptors.MethodDescriptor methodDescriptor = descriptorStore.getMethod(serviceName, methodName);
        if (methodDescriptor == null) {
            throw new GrpcException("unknown gRPC method: " + serviceName + "/" + methodName);
        }

        byte[] bodyBytes = request.getBodyAsRawBytes();
        if (bodyBytes == null || bodyBytes.length == 0) {
            recordGrpcServiceMethod(ctx, request, serviceName, methodName);
            // An empty-bodied gRPC request gets the same derived headers as any other, matching
            // GrpcHttp3Adapter. This branch previously returned the request untouched, which both
            // left a client-supplied x-grpc-service in place to be matched on and diverged from
            // HTTP/3, where the same request did get the path-derived tags.
            return GrpcDerivedHeaders.strip(request.clone())
                .withHeader(GrpcDerivedHeaders.SERVICE, serviceName)
                .withHeader(GrpcDerivedHeaders.METHOD, methodName)
                .withHeader(GrpcDerivedHeaders.ORIGINAL_CONTENT_TYPE, request.getFirstHeader("content-type"));
        }

        List<byte[]> messages = GrpcFrameCodec.decode(bodyBytes, request.getFirstHeader(GrpcStatusMapper.GRPC_ENCODING_HEADER), configuration);
        if (messages.isEmpty()) {
            throw new GrpcException("failed to decode gRPC frame from request body");
        }
        GrpcJsonMessageConverter converter = descriptorStore.getConverter();

        if (messages.size() == 1) {
            String json = converter.toJson(messages.get(0), methodDescriptor.getInputType());
            recordGrpcServiceMethod(ctx, request, serviceName, methodName);
            return GrpcDerivedHeaders.strip(request.clone())
                .withBody(json)
                .withHeader(GrpcDerivedHeaders.SERVICE, serviceName)
                .withHeader(GrpcDerivedHeaders.METHOD, methodName)
                .withHeader(GrpcDerivedHeaders.ORIGINAL_CONTENT_TYPE, request.getFirstHeader("content-type"));
        } else {
            StringBuilder jsonArray = new StringBuilder("[");
            for (int i = 0; i < messages.size(); i++) {
                if (i > 0) {
                    jsonArray.append(",");
                }
                jsonArray.append(converter.toJson(messages.get(i), methodDescriptor.getInputType()));
            }
            jsonArray.append("]");
            recordGrpcServiceMethod(ctx, request, serviceName, methodName);
            return GrpcDerivedHeaders.strip(request.clone())
                .withBody(jsonArray.toString())
                .withHeader(GrpcDerivedHeaders.SERVICE, serviceName)
                .withHeader(GrpcDerivedHeaders.METHOD, methodName)
                .withHeader(GrpcDerivedHeaders.ORIGINAL_CONTENT_TYPE, request.getFirstHeader("content-type"))
                .withHeader(GrpcDerivedHeaders.CLIENT_STREAMING, "true");
        }
    }

    /**
     * Records the resolved gRPC service/method in the per-connection {@link GrpcPendingRequests}
     * registry consumed by {@link GrpcToHttpResponseHandler#encode}, keyed by the request's
     * HTTP/2 stream id.
     * <p>
     * The key matters: in the default configuration both gRPC handlers sit on the
     * <strong>connection-level</strong> pipeline (the per-stream child-channel pipeline is only
     * installed when {@code grpcBidiStreamingEnabled} is on, which it is not by default), so a
     * single-slot channel attribute would be overwritten by every concurrent stream. See
     * {@link GrpcPendingRequests} for the full rationale, including the HTTP/1.1 fallback.
     */
    private static void recordGrpcServiceMethod(ChannelHandlerContext ctx, HttpRequest request, String serviceName, String methodName) {
        if (ctx != null && serviceName != null && !serviceName.isEmpty()
            && methodName != null && !methodName.isEmpty()) {
            GrpcPendingRequests pendingRequests = GrpcPendingRequests.forChannel(ctx.channel());
            Integer streamId = request != null ? request.getStreamId() : null;
            // Retain the original gRPC-Web request content-type (set by translateGrpcWebRequest)
            // so the matched-expectation response is re-framed as gRPC-Web. Without this the marker
            // -- present on the request only -- is lost, and a browser gRPC-Web client receives
            // application/grpc with grpc-status in HTTP trailers it cannot read.
            String grpcWebContentType = request != null ? request.getFirstHeader("x-grpc-web-content-type") : null;
            GrpcPendingRequests.PendingRequest pendingRequest =
                pendingRequests.record(streamId, serviceName, methodName,
                    isNotBlank(grpcWebContentType) ? grpcWebContentType : null);
            scheduleDeadline(ctx, request, pendingRequests, pendingRequest, streamId);
        }
    }

    /**
     * Honours the client's {@code grpc-timeout} by scheduling a DEADLINE_EXCEEDED response for when
     * the deadline elapses.
     * <p>
     * The header is still passed through as an ordinary request header so it stays matchable; this
     * only adds enforcement. The timer runs on the channel's own event loop, so it is serialised
     * with the response write -- {@code claimForDeadline} then guarantees exactly one of the two
     * wins, and a response that arrives after the deadline (the {@code Delay} that outran the
     * client) is dropped by {@link GrpcToHttpResponseHandler} rather than written as a second
     * response on a stream that already carries terminal trailers.
     * <p>
     * The timer is cancelled when the exchange is answered, when its record is evicted, and when
     * the connection goes inactive, so it cannot outlive the exchange.
     */
    private static void scheduleDeadline(
        ChannelHandlerContext ctx,
        HttpRequest request,
        GrpcPendingRequests pendingRequests,
        GrpcPendingRequests.PendingRequest pendingRequest,
        Integer streamId
    ) {
        Long timeoutNanos = GrpcTimeout.parseNanos(request);
        if (timeoutNanos == null) {
            return;
        }
        String grpcWebContentType = request.getFirstHeader("x-grpc-web-content-type");
        pendingRequest.deadlineFuture(ctx.channel().eventLoop().schedule(() -> {
            if (!pendingRequests.claimForDeadline(streamId)) {
                // the response was already written -- nothing to cancel
                return;
            }
            org.mockserver.model.HttpResponse deadlineResponse = org.mockserver.model.HttpResponse.response()
                .withStatusCode(200)
                .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                // marks this as the deadline response itself, so GrpcToHttpResponseHandler.encode
                // passes it through instead of mistaking it for the late response it pre-empts
                .withHeader(GrpcToHttpResponseHandler.DEADLINE_RESPONSE_HEADER, "true")
                .withTrailer(GrpcStatusMapper.GRPC_STATUS_HEADER,
                    String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode()))
                .withTrailer(GrpcStatusMapper.GRPC_MESSAGE_HEADER,
                    GrpcStatusMapper.percentEncodeMessage(GrpcTimeout.deadlineExceededMessage(timeoutNanos)));
            tagGrpcWebResponse(deadlineResponse, request, isNotBlank(grpcWebContentType) ? grpcWebContentType : null);
            ctx.writeAndFlush(deadlineResponse);
        }, timeoutNanos, TimeUnit.NANOSECONDS));
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isEmpty();
    }

    /**
     * Builds a gRPC fault response applying omitGrpcStatus, corruptGrpcStatus, and customTrailers
     * modifiers from the chaos profile.
     * <p>
     * {@code grpc-status}/{@code grpc-message} and the custom trailers are emitted as real HTTP
     * trailers (a terminal HEADERS frame on HTTP/2), matching the gRPC wire contract. This is what
     * makes {@code omitGrpcStatus} a genuine fault simulation: the non-faulted case emits a
     * trailer, so omitting it is observably a missing terminal status.
     */
    private static org.mockserver.model.HttpResponse buildFaultResponse(
        GrpcChaosProfile profile,
        GrpcStatusMapper.GrpcStatusCode statusCode,
        String message
    ) {
        org.mockserver.model.HttpResponse response = org.mockserver.model.HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);

        if (Boolean.TRUE.equals(profile.getOmitGrpcStatus())) {
            // intentionally omit the grpc-status trailer (simulates broken/incomplete RPC)
        } else if (Boolean.TRUE.equals(profile.getCorruptGrpcStatus())) {
            // send a non-numeric grpc-status value — a genuine protocol violation
            // (gRPC spec requires grpc-status to be a decimal integer)
            response.withTrailer(GrpcStatusMapper.GRPC_STATUS_HEADER, "malformed");
            response.withTrailer(GrpcStatusMapper.GRPC_MESSAGE_HEADER, GrpcStatusMapper.percentEncodeMessage(message));
        } else {
            response.withTrailer(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(statusCode.getCode()));
            response.withTrailer(GrpcStatusMapper.GRPC_MESSAGE_HEADER, GrpcStatusMapper.percentEncodeMessage(message));
        }

        // inject custom trailers (belt-and-braces: skip entries with CR/LF
        // to prevent header/response splitting even if validation was bypassed)
        java.util.Map<String, String> customTrailers = profile.getCustomTrailers();
        if (customTrailers != null) {
            for (java.util.Map.Entry<String, String> entry : customTrailers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || key.isEmpty()
                    || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0
                    || (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0))) {
                    continue; // skip malformed entries defensively
                }
                response.withTrailer(key, value);
            }
        }

        return response;
    }

    /**
     * Sends the fault response, optionally delaying by the profile's latencyMs.
     */
    private static void scheduleFaultResponse(ChannelHandlerContext ctx, GrpcChaosProfile profile,
                                              org.mockserver.model.HttpResponse response) {
        Long latencyMs = profile.getLatencyMs();
        if (latencyMs != null && latencyMs > 0) {
            ctx.channel().eventLoop().schedule(() -> ctx.writeAndFlush(response), latencyMs, TimeUnit.MILLISECONDS);
        } else {
            ctx.writeAndFlush(response);
        }
    }

    static String[] parseGrpcPath(String path) {
        if (path == null || path.isEmpty()) {
            return new String[]{"", ""};
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        int slashIndex = path.lastIndexOf('/');
        if (slashIndex < 1 || slashIndex == path.length() - 1) {
            return new String[]{path, ""};
        }
        return new String[]{path.substring(0, slashIndex), path.substring(slashIndex + 1)};
    }
}

package org.mockserver.netty.mcp;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.cors.CORSHeaders;
import org.mockserver.cors.CORSOriginPolicy;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.authentication.AuthenticationResult;
import org.mockserver.authentication.ControlPlaneAuthentication;
import org.mockserver.configuration.ControlPlaneAuthenticationSettings;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.JDKCertificateToMockServerX509Certificate;
import org.mockserver.mock.HttpState;
import org.mockserver.mappers.Http2StreamIds;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.socket.tls.SniHandler;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;

/**
 * Netty channel handler that intercepts MCP (Model Context Protocol) requests
 * on the TCP (HTTP/1.1 and HTTP/2) path.
 * <p>
 * All MCP protocol logic (JSON-RPC parsing, session management, tool/resource
 * dispatch) is delegated to the transport-neutral {@link McpRequestProcessor}.
 * This handler is responsible only for the Netty HTTP/1.1 framing: reading
 * {@link FullHttpRequest}, writing {@link FullHttpResponse}, CORS, and
 * control-plane authentication.
 */
@ChannelHandler.Sharable
public class McpStreamableHttpHandler extends ChannelInboundHandlerAdapter {

    // Per-request CORS context, captured at request entry so responses written
    // deeper in the handler can echo the requesting origin / requested headers.
    private static final AttributeKey<String> CORS_ORIGIN = AttributeKey.valueOf("mockserver.mcp.cors.origin");
    private static final AttributeKey<String> CORS_REQUEST_HEADERS = AttributeKey.valueOf("mockserver.mcp.cors.requestHeaders");
    private static final AttributeKey<String> CORS_HOST = AttributeKey.valueOf("mockserver.mcp.cors.host");

    private final HttpState httpState;
    private final McpSessionManager sessionManager;
    private final McpRequestProcessor processor;
    private final MockServerLogger mockServerLogger;

    public McpStreamableHttpHandler(HttpState httpState, LifeCycle server, McpSessionManager sessionManager) {
        this(httpState, sessionManager, new McpRequestProcessor(httpState, server, sessionManager));
    }

    /**
     * Constructor taking an explicit {@link McpRequestProcessor}, so tests can drive the Netty
     * framing with a processor configured to fail in a controlled way.
     */
    McpStreamableHttpHandler(HttpState httpState, McpSessionManager sessionManager, McpRequestProcessor processor) {
        this.httpState = httpState;
        this.sessionManager = sessionManager;
        this.processor = processor;
        this.mockServerLogger = httpState.getMockServerLogger();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        boolean release = true;
        try {
            if (msg instanceof FullHttpRequest) {
                FullHttpRequest request = (FullHttpRequest) msg;
                String uri = request.uri();
                if (McpRequestProcessor.isMcpPath(uri)) {
                    handleMcpRequest(ctx, request);
                    return;
                }
            }
            release = false;
            ctx.fireChannelRead(msg);
        } finally {
            if (release) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
        // a mid-pipeline handler that swallows channelReadComplete starves Netty's HTTP/2
        // flow-control flush (Http2ConnectionHandler.channelReadComplete -> writePendingBytes),
        // stalling any h2 response larger than the peer's initial window - so propagate the event
        ctx.fireChannelReadComplete();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (directMemoryLimitReached(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + cause.getMessage())
            );
            ServerClosedChannels.markClosed(ctx.channel());
        } else if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by MCP handler")
                    .setThrowable(cause)
            );
            ServerClosedChannels.markClosed(ctx.channel());
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("SSL or decoder fault caught by MCP handler" + sniDescription(ctx.channel()))
                        .setThrowable(boundedFault(cause))
                );
            }
            ServerClosedChannels.markClosed(ctx.channel());
        }
        ctx.close();
    }

    private void handleMcpRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        // Capture the CORS context per request so responses written deep in the
        // handlers can echo it; the dashboard may be served from another origin.
        String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        ctx.channel().attr(CORS_ORIGIN).set(origin);
        ctx.channel().attr(CORS_REQUEST_HEADERS).set(request.headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS));
        String host = CORSOriginPolicy.consistentHost(request.headers().getAll(HttpHeaderNames.HOST));
        ctx.channel().attr(CORS_HOST).set(host);
        // On HTTP/2 the response must be written on the stream the request arrived on, so capture
        // the inbound stream id once and thread it through every write path below. It is captured as
        // a plain Integer rather than by holding the request, because the POST path answers from a
        // background executor after the request buffer has been released. Null on HTTP/1.1.
        final Integer streamId = Http2StreamIds.streamIdOf(request);
        HttpMethod method = request.method();
        boolean corsPreflight = method.equals(HttpMethod.OPTIONS) && origin != null && !origin.isEmpty()
            && request.headers().contains(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD);
        if (httpState.isControlPlaneOriginRefused(method.name(), new QueryStringDecoder(request.uri()).path(), origin, host, corsPreflight)) {
            writeMcpResult(ctx, new McpRequestProcessor.McpResult(403,
                ("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"" + HttpState.CONTROL_PLANE_ORIGIN_REFUSED_MESSAGE + "\"},\"id\":null}").getBytes(StandardCharsets.UTF_8), null), streamId);
            return;
        }
        if (method.equals(HttpMethod.OPTIONS) && origin != null && !origin.isEmpty()) {
            // CORS preflight from a browser
            McpRequestProcessor.McpResult result = processor.handleOptions(true);
            writeMcpResult(ctx, result, streamId);
        } else if (method.equals(HttpMethod.POST)) {
            handlePost(ctx, request, streamId);
        } else if (method.equals(HttpMethod.GET)) {
            if (!authenticateRequest(ctx, request, streamId)) {
                return;
            }
            McpRequestProcessor.McpResult result = processor.handleGet();
            writeMcpResult(ctx, result, streamId);
        } else if (method.equals(HttpMethod.DELETE)) {
            if (!authenticateRequest(ctx, request, streamId)) {
                return;
            }
            String sessionId = request.headers().get("Mcp-Session-Id");
            McpRequestProcessor.McpResult result = processor.handleDelete(sessionId);
            writeMcpResult(ctx, result, streamId);
        } else {
            McpRequestProcessor.McpResult result = processor.handleOptions(false);
            writeMcpResult(ctx, result, streamId);
        }
    }

    /**
     * Echo CORS headers onto an MCP response for an origin {@link CORSOriginPolicy} allows.
     */
    private void addCorsHeaders(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpResponse response) {
        String origin = ctx.channel().attr(CORS_ORIGIN).get();
        if (origin == null || origin.isEmpty()
            || !CORSOriginPolicy.isControlPlaneOriginAllowed(httpState.getConfiguration().controlPlaneCorsAllowOrigin(), httpState.getConfiguration().controlPlaneTrustedHostSuffixes(), origin, ctx.channel().attr(CORS_HOST).get())) {
            return;
        }
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, CORSHeaders.DEFAULT_ALLOW_METHODS);
        String requestedHeaders = ctx.channel().attr(CORS_REQUEST_HEADERS).get();
        String allowHeaders = (requestedHeaders != null && !requestedHeaders.isEmpty())
            ? requestedHeaders : CORSHeaders.DEFAULT_ALLOW_HEADERS;
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, allowHeaders);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, "Mcp-Session-Id, " + CORSHeaders.DEFAULT_ALLOW_HEADERS);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "300");
    }

    /**
     * Whether the connection carrying {@code channel} is TLS: an HTTP/2 stream channel takes its parent's state.
     */
    static boolean isSecure(io.netty.channel.Channel channel) {
        for (io.netty.channel.Channel current = channel; current != null; current = current.parent()) {
            if (org.mockserver.netty.unification.PortUnificationHandler.isSslEnabledUpstream(current)) {
                return true;
            }
        }
        return false;
    }

    private boolean authenticateRequest(ChannelHandlerContext ctx, FullHttpRequest request, Integer streamId) {
        return authenticate(ctx, request, streamId) != null;
    }

    /**
     * Authenticate a control-plane MCP request. Returns a {@link ControlPlaneAuthentication} (the
     * result, whose verified scopes drive per-tool authorization on the POST path, and the settings
     * snapshot it was authenticated under) when authentication succeeds, or {@code null} after having
     * written the 401 rejection when it fails. When no authentication handler is configured, the result
     * is authenticated-but-anonymous (no scopes) so the caller proceeds unchanged.
     */
    private ControlPlaneAuthentication authenticate(ChannelHandlerContext ctx, FullHttpRequest request, Integer streamId) {
        ControlPlaneAuthenticationSettings settings = httpState.controlPlaneAuthenticationSettings();
        AuthenticationHandler authHandler = httpState.getControlPlaneAuthenticationHandler(settings);
        if (authHandler == null) {
            return ControlPlaneAuthentication.of(settings, AuthenticationResult.authenticated(null, "none", java.util.Map.of(), java.util.Set.of()));
        }
        try {
            HttpRequest mockRequest = HttpRequest.request()
                .withMethod(request.method().name())
                .withPath(request.uri())
                .withSecure(isSecure(ctx.channel()))
                .markControlPlaneRequest();
            if (ctx.channel().remoteAddress() != null) {
                mockRequest.withRemoteAddress(org.apache.commons.lang3.Strings.CS.removeStart(ctx.channel().remoteAddress().toString(), "/"));
            }
            mockRequest.withLogCorrelationId(org.mockserver.uuid.UUIDService.getNonSecureUUID());
            for (Map.Entry<String, String> header : request.headers()) {
                mockRequest.withHeader(header.getKey(), header.getValue());
            }
            Certificate[] clientCertificates = SniHandler.retrieveClientCertificates(mockServerLogger, ctx);
            if (clientCertificates != null) {
                new JDKCertificateToMockServerX509Certificate(mockServerLogger)
                    .setClientCertificates(mockRequest, clientCertificates);
            }
            // Use the richer authenticate() SPI: existing/legacy boolean handlers are adapted
            // by its default method to an authenticated-but-anonymous result (no scopes), so
            // behaviour is unchanged for them; OIDC-style handlers surface verified scopes that
            // drive per-tool control-plane authorization.
            AuthenticationResult result = authHandler.authenticate(mockRequest);
            if (!result.isAuthenticated()) {
                writeUnauthorized(ctx, streamId);
                return null;
            }
            return ControlPlaneAuthentication.of(settings, result);
        } catch (AuthenticationException e) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("MCP authentication failed: {}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
            }
            writeUnauthorized(ctx, streamId);
            return null;
        }
    }

    private void handlePost(ChannelHandlerContext ctx, FullHttpRequest request, Integer streamId) {
        final ControlPlaneAuthentication authentication = authenticate(ctx, request, streamId);
        if (authentication == null) {
            return;
        }
        // Retain the request before handing off to the MCP executor since Netty will release
        // the buffer after channelRead returns. The finally block ensures it is always released.
        request.retain();
        try {
            sessionManager.getExecutor().execute(() -> {
                try {
                    String body = request.content().toString(StandardCharsets.UTF_8);
                    String mcpSessionId = request.headers().get("Mcp-Session-Id");
                    McpRequestProcessor.McpResult result = processor.handlePost(body, mcpSessionId, authentication);
                    writeMcpResult(ctx, result, streamId);
                } catch (Throwable throwable) {
                    // Backstop: anything escaping here would otherwise be swallowed by the executor
                    // with no response written, leaving the client blocked until its own timeout.
                    // Per-method failures are already converted to JSON-RPC errors inside the
                    // processor; this covers the residue (serialisation, session lookup, writes).
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("exception processing MCP request")
                            .setThrowable(throwable)
                    );
                    writeInternalError(ctx, streamId);
                } finally {
                    request.release();
                }
            });
        } catch (RejectedExecutionException e) {
            request.release();
            McpRequestProcessor.McpResult busy = new McpRequestProcessor.McpResult(503,
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Server is busy, try again later\"},\"id\":null}".getBytes(StandardCharsets.UTF_8),
                null);
            writeMcpResult(ctx, busy, streamId);
        }
    }

    /**
     * Write a JSON-RPC {@code -32603 Internal error} envelope. Used as the last-resort response
     * so a failure on the MCP executor can never leave the client without a response. The HTTP/2
     * stream id is threaded through for the same reason every other write on this path carries it:
     * without it the response is not delivered to an HTTP/2 client, which would reinstate the
     * original hang on exactly the error path this backstop exists to cover.
     */
    private void writeInternalError(ChannelHandlerContext ctx, Integer streamId) {
        try {
            McpRequestProcessor.McpResult result = new McpRequestProcessor.McpResult(500,
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"},\"id\":null}".getBytes(StandardCharsets.UTF_8),
                null);
            writeMcpResult(ctx, result, streamId);
        } catch (Throwable ignored) {
            // the channel is already unusable -- close it so the client fails fast rather than waiting
            ctx.close();
        }
    }

    private void writeUnauthorized(ChannelHandlerContext ctx, Integer streamId) {
        byte[] body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(
                JsonRpcMessage.JsonRpcResponse.error(null, JsonRpcMessage.INVALID_REQUEST, "Unauthorized for control plane"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            body = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Unauthorized for control plane\"},\"id\":null}".getBytes(StandardCharsets.UTF_8);
        }
        McpRequestProcessor.McpResult result = new McpRequestProcessor.McpResult(401, body, null);
        writeMcpResult(ctx, result, streamId);
    }

    /**
     * Translate a transport-neutral {@link McpRequestProcessor.McpResult} into a
     * Netty HTTP/1.1 response and write it to the channel.
     */
    private void writeMcpResult(ChannelHandlerContext ctx, McpRequestProcessor.McpResult result, Integer streamId) {
        HttpResponseStatus status = HttpResponseStatus.valueOf(result.getStatusCode());
        DefaultFullHttpResponse response;
        if (result.hasBody()) {
            response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(result.getBody())
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            HttpUtil.setContentLength(response, result.getBody().length);
        } else {
            response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER
            );
            HttpUtil.setContentLength(response, 0);
        }
        if (result.getSessionId() != null) {
            response.headers().set("Mcp-Session-Id", result.getSessionId());
        }
        addCorsHeaders(ctx, response);
        // This handler builds a raw Netty response and never reaches the response mapper, so it must
        // stamp the HTTP/2 stream id itself - otherwise the HTTP/2 codec routes the reply onto a new
        // server-initiated stream and the MCP client hangs waiting for a response that never lands.
        Http2StreamIds.stamp(response, streamId);
        ctx.writeAndFlush(response);
    }
}

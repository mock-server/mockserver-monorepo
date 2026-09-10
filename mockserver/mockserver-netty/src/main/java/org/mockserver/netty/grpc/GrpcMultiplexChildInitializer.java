package org.mockserver.netty.grpc;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import org.mockserver.codec.MockServerHttpServerCodec;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.mcp.McpSessionManager;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.netty.unification.AltSvcHeaderHandler;
import org.mockserver.netty.unification.ConnectionScopeHandler;
import org.mockserver.netty.unification.StreamAddressedContentHandler;
import org.mockserver.netty.unification.TraceContextHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;

import java.security.cert.Certificate;

/**
 * Per-stream child initializer used with {@link io.netty.handler.codec.http2.Http2MultiplexHandler}
 * when the gRPC bidi-streaming multiplex pipeline is enabled.
 * <p>
 * <strong>Phase 3a:</strong> installs {@link GrpcBidiRouterHandler} as the first handler
 * (after LOCAL_HOST_HEADERS propagation). The router inspects the first HEADERS frame per
 * stream and decides whether to install the bidi streaming handler (for true bidi methods)
 * or the existing re-aggregating chain (for unary, server-streaming, client-streaming, and
 * non-gRPC streams). Non-bidi streams are byte-for-byte identical to the Phase 0 path.
 * <p>
 * The re-aggregating chain ({@link Http2StreamFrameToHttpObjectCodec} +
 * {@link HttpObjectAggregator} + downstream handlers) is factored into the static
 * {@link #installReAggregatingChain} method so the router can install it for non-bidi
 * streams.
 */
public class GrpcMultiplexChildInitializer extends ChannelInitializer<Http2StreamChannel> {

    private final Configuration configuration;
    private final LifeCycle server;
    private final HttpState httpState;
    private final HttpActionHandler actionHandler;
    private final MockServerLogger mockServerLogger;
    private final McpSessionManager mcpSessionManager;
    private final boolean sslEnabled;
    private final Certificate[] clientCertificates;

    // Sharable handler instances -- reused across child channels (same as the existing h2 branch)
    private final CallbackWebSocketServerHandler callbackWebSocketServerHandler;
    private final DashboardWebSocketHandler dashboardWebSocketHandler;
    private final TraceContextHandler traceContextHandler;
    private final AltSvcHeaderHandler altSvcHeaderHandler;
    private final GrpcToHttpResponseHandler grpcToHttpResponseHandler;
    private final GrpcToHttpRequestHandler grpcToHttpRequestHandler;
    private final HttpRequestHandler httpRequestHandler;
    private final McpStreamableHttpHandler mcpStreamableHttpHandler;

    // Descriptor store for bidi routing
    private final GrpcProtoDescriptorStore descriptorStore;

    public GrpcMultiplexChildInitializer(
        Configuration configuration,
        LifeCycle server,
        HttpState httpState,
        HttpActionHandler actionHandler,
        MockServerLogger mockServerLogger,
        McpSessionManager mcpSessionManager,
        boolean sslEnabled,
        Certificate[] clientCertificates
    ) {
        this.configuration = configuration;
        this.server = server;
        this.httpState = httpState;
        this.actionHandler = actionHandler;
        this.mockServerLogger = mockServerLogger;
        this.mcpSessionManager = mcpSessionManager;
        this.sslEnabled = sslEnabled;
        this.clientCertificates = clientCertificates;

        // Pre-build sharable handlers -- mirrors the instances created in switchToHttp2/switchToH2c
        this.callbackWebSocketServerHandler = new CallbackWebSocketServerHandler(httpState);
        this.dashboardWebSocketHandler = new DashboardWebSocketHandler(httpState, sslEnabled, false);
        this.traceContextHandler = new TraceContextHandler(configuration);
        int h3Port = configuration.http3Port();
        if (h3Port > 0 && configuration.http3AdvertiseAltSvc()) {
            this.altSvcHeaderHandler = new AltSvcHeaderHandler(h3Port, configuration.http3AltSvcMaxAge());
        } else {
            this.altSvcHeaderHandler = null;
        }
        this.httpRequestHandler = new HttpRequestHandler(configuration, server, httpState, actionHandler);

        this.descriptorStore = httpState.getGrpcDescriptorStore();
        if (descriptorStore != null && descriptorStore.hasServices()) {
            this.grpcToHttpResponseHandler = new GrpcToHttpResponseHandler(mockServerLogger, descriptorStore);
            this.grpcToHttpRequestHandler = new GrpcToHttpRequestHandler(mockServerLogger, descriptorStore);
        } else {
            this.grpcToHttpResponseHandler = null;
            this.grpcToHttpRequestHandler = null;
        }

        if (configuration.mcpEnabled()) {
            this.mcpStreamableHttpHandler = new McpStreamableHttpHandler(httpState, server, mcpSessionManager);
        } else {
            this.mcpStreamableHttpHandler = null;
        }
    }

    @Override
    protected void initChannel(Http2StreamChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // HTTP/2 child channels do NOT inherit parent-channel attributes (AbstractHttp2StreamChannel
        // delegates localAddress()/remoteAddress() to the parent but not the attribute map). Install
        // ConnectionScopeHandler as the FIRST handler so it copies ALL connection-scoped attributes
        // (protocol negotiation, TLS state, client certificates, proxying flag, local-host set, ...)
        // from the parent connection channel onto this child stream channel before any downstream
        // handler reads them. It copies once and removes itself. See ConnectionScopeHandler for the
        // full list and rationale; without it protocol detection, WebSocket-501, mTLS control-plane
        // auth, and proxy routing all misbehave on multiplexed HTTP/2 streams.
        pipeline.addLast("connectionScope", ConnectionScopeHandler.INSTANCE);

        // Install the router handler which inspects the first HEADERS frame per stream
        // and decides whether to use the bidi streaming path or re-aggregating path.
        // The router passes all sharable handler references so it can install the
        // re-aggregating chain for non-bidi streams.
        pipeline.addLast("grpcBidiRouter", new GrpcBidiRouterHandler(
            configuration,
            descriptorStore,
            mockServerLogger,
            sslEnabled,
            clientCertificates,
            callbackWebSocketServerHandler,
            dashboardWebSocketHandler,
            mcpStreamableHttpHandler,
            traceContextHandler,
            altSvcHeaderHandler,
            grpcToHttpResponseHandler,
            grpcToHttpRequestHandler,
            httpRequestHandler,
            httpState
        ));
    }

    /**
     * Installs the Phase 0 re-aggregating chain into the given pipeline: converts
     * HTTP/2 stream frames back into {@code FullHttpRequest}/{@code FullHttpResponse}
     * and adds the downstream handler chain (WebSocket, MCP, gRPC codec, request handler).
     * <p>
     * This is the same chain that {@code initChannel()} installed unconditionally in
     * Phases 0-2. It is now extracted so {@link GrpcBidiRouterHandler} can install it
     * for non-bidi streams while the router installs {@link GrpcBidiStreamHandler} for
     * bidi streams.
     *
     * @param pipeline                      the child channel's pipeline
     * @param configuration                 server configuration
     * @param mockServerLogger              logger
     * @param sslEnabled                    whether TLS is enabled
     * @param clientCertificates            client certificates (may be null)
     * @param channel                       the child channel (used for localAddress)
     * @param callbackWebSocketServerHandler sharable WebSocket callback handler
     * @param dashboardWebSocketHandler     sharable dashboard WebSocket handler
     * @param mcpStreamableHttpHandler      sharable MCP handler (may be null)
     * @param traceContextHandler           sharable trace context handler
     * @param altSvcHeaderHandler           sharable Alt-Svc header handler (may be null)
     * @param grpcToHttpResponseHandler     sharable gRPC response handler (may be null)
     * @param grpcToHttpRequestHandler      sharable gRPC request handler (may be null)
     * @param httpRequestHandler            sharable HTTP request handler
     */
    static void installReAggregatingChain(
        ChannelPipeline pipeline,
        Configuration configuration,
        MockServerLogger mockServerLogger,
        boolean sslEnabled,
        Certificate[] clientCertificates,
        Channel channel,
        CallbackWebSocketServerHandler callbackWebSocketServerHandler,
        DashboardWebSocketHandler dashboardWebSocketHandler,
        McpStreamableHttpHandler mcpStreamableHttpHandler,
        TraceContextHandler traceContextHandler,
        AltSvcHeaderHandler altSvcHeaderHandler,
        GrpcToHttpResponseHandler grpcToHttpResponseHandler,
        GrpcToHttpRequestHandler grpcToHttpRequestHandler,
        HttpRequestHandler httpRequestHandler
    ) {
        // Re-aggregate stream frames into FullHttpRequest/FullHttpResponse
        pipeline.addLast(new Http2StreamFrameToHttpObjectCodec(true));
        // Sits between the aggregator and the codec on the OUTBOUND path (addLast is head->tail,
        // outbound writes travel tail->head, so a handler added immediately after the codec
        // intercepts writes before the codec does; HttpObjectAggregator is inbound-only so this does
        // not disturb inbound aggregation). Streaming responses (SSE/NDJSON/AWS-event-stream, and so
        // all LLM streaming) are written as StreamAddressedHttpContent because getStreamId() is
        // non-null on the multiplex path. Without this translation the terminal frame's
        // endStream=true is silently discarded by Http2StreamFrameToHttpObjectCodec (its bare
        // HttpContent branch hard-codes endStream=false), the stream never closes, and the client
        // hangs until it times out with nothing logged. See StreamAddressedContentHandler.
        pipeline.addLast(StreamAddressedContentHandler.INSTANCE);
        pipeline.addLast(new HttpObjectAggregator(configuration.maxRequestBodySize()));

        // Downstream chain -- identical to the existing switchToHttp2/switchToH2c post-adapter chain
        pipeline.addLast(callbackWebSocketServerHandler);
        pipeline.addLast(dashboardWebSocketHandler);
        if (mcpStreamableHttpHandler != null) {
            pipeline.addLast(mcpStreamableHttpHandler);
        }
        // MockServerHttpServerCodec is NOT @Sharable -- create a fresh instance per child channel
        java.net.SocketAddress localAddress = channel.parent() != null
            ? channel.parent().localAddress()
            : channel.localAddress();
        pipeline.addLast(new MockServerHttpServerCodec(
            configuration, mockServerLogger, sslEnabled, clientCertificates, localAddress
        ));
        pipeline.addLast(traceContextHandler);
        if (altSvcHeaderHandler != null) {
            pipeline.addLast(altSvcHeaderHandler);
        }
        if (grpcToHttpResponseHandler != null) {
            pipeline.addLast(grpcToHttpResponseHandler);
            pipeline.addLast(grpcToHttpRequestHandler);
        }
        pipeline.addLast(httpRequestHandler);
    }
}

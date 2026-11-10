package org.mockserver.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.RawNetworkTrafficLog;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.connection.InboundConnectionIdleHandler;
import org.mockserver.netty.connection.WriteStallTimeoutHandler;
import org.mockserver.netty.mcp.McpSessionManager;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.netty.proxy.ProxyProtocolOriginalDestinationHandler;
import org.mockserver.netty.proxy.TransparentProxyHandler;
import org.mockserver.netty.unification.BinaryAwareRecvByteBufAllocator;
import org.mockserver.netty.unification.PortUnificationHandler;
import org.mockserver.proxyconfiguration.ProxyProtocolTrustedSources;
import org.mockserver.socket.ReadAfterFailedWrite;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;

@ChannelHandler.Sharable
public class MockServerUnificationInitializer extends ChannelHandlerAdapter {
    private final Configuration configuration;
    private final LifeCycle server;
    private final HttpState httpState;
    private final HttpActionHandler actionHandler;
    private final NettySslContextFactory nettySslContextFactory;
    private final McpSessionManager mcpSessionManager;
    // Built once and reused for every connection: the handler is @Sharable with no per-connection
    // state, but its McpToolRegistry is a large tool/schema tree. Null when MCP is disabled.
    private final McpStreamableHttpHandler mcpStreamableHttpHandler;
    // null unless logRawNetworkTraffic was on at startup, so no connection reads the property
    private final RawNetworkTrafficLog rawNetworkTrafficLog;
    // parsed whether or not transparentProxyEnabled is on now, as a PUT can turn that on later; null trusts any source
    private final ProxyProtocolTrustedSources proxyProtocolTrustedSources;
    private final ProxyProtocolOriginalDestinationHandler.RefusalWarnings proxyProtocolRefusalWarnings = new ProxyProtocolOriginalDestinationHandler.RefusalWarnings();

    public MockServerUnificationInitializer(Configuration configuration, LifeCycle server, HttpState httpState, HttpActionHandler actionHandler, NettySslContextFactory nettySslContextFactory) {
        this(configuration, server, httpState, actionHandler, nettySslContextFactory, RawNetworkTrafficLog.forConfiguration(configuration, RawNetworkTrafficLog.SERVER_SIDE));
    }

    public MockServerUnificationInitializer(Configuration configuration, LifeCycle server, HttpState httpState, HttpActionHandler actionHandler, NettySslContextFactory nettySslContextFactory, RawNetworkTrafficLog rawNetworkTrafficLog) {
        this.configuration = configuration;
        this.server = server;
        this.httpState = httpState;
        this.actionHandler = actionHandler;
        this.nettySslContextFactory = nettySslContextFactory;
        this.mcpSessionManager = new McpSessionManager(httpState.getMockServerLogger());
        this.mcpStreamableHttpHandler = configuration.mcpEnabled()
            ? new McpStreamableHttpHandler(httpState, server, mcpSessionManager)
            : null;
        this.rawNetworkTrafficLog = rawNetworkTrafficLog;
        this.proxyProtocolTrustedSources = ProxyProtocolTrustedSources.parse(configuration.proxyProtocolTrustedSources());
        if (proxyProtocolTrustedSources != null && httpState.getMockServerLogger().isEnabledForInstance(Level.INFO)) {
            httpState.getMockServerLogger().logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("accepting PROXY protocol headers only from proxyProtocolTrustedSources:{}")
                    .setArguments(proxyProtocolTrustedSources)
            );
        }
    }

    public McpSessionManager getMcpSessionManager() {
        return mcpSessionManager;
    }

    public HttpActionHandler getActionHandler() {
        return actionHandler;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        // before the connection's first read, which is when Netty fixes how its read buffers are sized
        BinaryAwareRecvByteBufAllocator.install(ctx.channel());
        long idleTimeoutMillis = configuration.inboundConnectionIdleTimeoutMillis();
        if (idleTimeoutMillis > 0) {
            ctx.pipeline().addFirst("inbound-idle", new InboundConnectionIdleHandler(idleTimeoutMillis, httpState.getMockServerLogger()));
        }
        long writeStallTimeoutMillis = configuration.responseWriteStallTimeoutMillis();
        if (writeStallTimeoutMillis > 0) {
            ctx.pipeline().addFirst("write-stall", new WriteStallTimeoutHandler(writeStallTimeoutMillis, httpState.getMockServerLogger()));
        }
        // a failed write ends the connection's output, not the connection: requests already received are still read
        ReadAfterFailedWrite.install(ctx.channel());
        // The PROXY protocol handler goes in front of port unification, so that what follows the header is
        // classified, not the header itself, which begins none of the protocols port unification detects
        if (Boolean.TRUE.equals(configuration.transparentProxyEnabled())) {
            ctx.pipeline().addBefore(ctx.name(), "proxy-protocol", new ProxyProtocolOriginalDestinationHandler(httpState.getMockServerLogger(), proxyProtocolTrustedSources, proxyProtocolRefusalWarnings));
            ctx.pipeline().addLast("transparent-proxy", new TransparentProxyHandler(configuration, httpState.getMockServerLogger()));
        }
        ctx.pipeline().replace(this, null, new PortUnificationHandler(configuration, server, httpState, actionHandler, nettySslContextFactory, mcpStreamableHttpHandler, rawNetworkTrafficLog));
    }
}

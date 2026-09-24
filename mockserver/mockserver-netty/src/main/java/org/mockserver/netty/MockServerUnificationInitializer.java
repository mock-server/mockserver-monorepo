package org.mockserver.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.mcp.McpSessionManager;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.netty.proxy.ProxyProtocolOriginalDestinationHandler;
import org.mockserver.netty.proxy.TransparentProxyHandler;
import org.mockserver.netty.unification.PortUnificationHandler;
import org.mockserver.socket.tls.NettySslContextFactory;

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

    public MockServerUnificationInitializer(Configuration configuration, LifeCycle server, HttpState httpState, HttpActionHandler actionHandler, NettySslContextFactory nettySslContextFactory) {
        this.configuration = configuration;
        this.server = server;
        this.httpState = httpState;
        this.actionHandler = actionHandler;
        this.nettySslContextFactory = nettySslContextFactory;
        this.mcpSessionManager = new McpSessionManager(httpState.getMockServerLogger());
        this.mcpStreamableHttpHandler = configuration.mcpEnabled()
            ? new McpStreamableHttpHandler(httpState, server, mcpSessionManager)
            : null;
    }

    public McpSessionManager getMcpSessionManager() {
        return mcpSessionManager;
    }

    public HttpActionHandler getActionHandler() {
        return actionHandler;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        // When transparent proxy mode is enabled, add:
        // 1. PROXY protocol v1 handler (reads first inbound bytes for PROXY header)
        // 2. Transparent proxy handler (resolves via conntrack at channelActive)
        // The PROXY protocol handler is first so it can detect and parse headers
        // before the transparent proxy handler fires its channel-level resolution.
        if (Boolean.TRUE.equals(configuration.transparentProxyEnabled())) {
            ctx.pipeline().addLast("proxy-protocol", new ProxyProtocolOriginalDestinationHandler(httpState.getMockServerLogger()));
            ctx.pipeline().addLast("transparent-proxy", new TransparentProxyHandler(configuration, httpState.getMockServerLogger()));
        }
        ctx.pipeline().replace(this, null, new PortUnificationHandler(configuration, server, httpState, actionHandler, nettySslContextFactory, mcpStreamableHttpHandler));
    }
}

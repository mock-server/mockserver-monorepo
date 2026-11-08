package org.mockserver.netty.proxy.socks;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4CommandRequest;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
import io.netty.handler.codec.socksx.v4.Socks4CommandType;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static org.mockserver.authentication.ProxyAuthenticationValidator.proxyAuthenticationConfigured;
import static org.mockserver.log.model.LogEntry.LogMessageType.AUTHENTICATION_FAILED;

@ChannelHandler.Sharable
public class Socks4ProxyHandler extends SocksProxyHandler<Socks4CommandRequest> {

    public Socks4ProxyHandler(Configuration configuration, MockServerLogger mockServerLogger, LifeCycle server) {
        super(configuration, mockServerLogger, server);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final Socks4CommandRequest commandRequest) {
        if (proxyAuthenticationConfigured(configuration.proxyAuthenticationUsername(), configuration.proxyAuthenticationPassword())) {
            // SOCKS4 cannot carry a password, so with proxy authentication configured it is refused
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(AUTHENTICATION_FAILED)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("SOCKS4 request from:{}refused because proxy authentication is configured and SOCKS4 cannot carry a password; use SOCKS5 with username/password authentication, or an HTTP CONNECT with a Proxy-Authorization header")
                        .setArguments(ctx.channel().remoteAddress())
                );
            }
            refuse(ctx, new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED));
        } else if (commandRequest.type().equals(Socks4CommandType.CONNECT)) {
            forwardConnection(ctx, new Socks4ConnectHandler(configuration, mockServerLogger, server, commandRequest.dstAddr(), commandRequest.dstPort()), commandRequest.dstAddr(), commandRequest.dstPort());
            ctx.fireChannelRead(commandRequest);
        } else {
            refuse(ctx, new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED));
        }
    }
}

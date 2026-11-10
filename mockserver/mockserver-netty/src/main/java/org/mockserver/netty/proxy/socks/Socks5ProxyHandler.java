package org.mockserver.netty.proxy.socks;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.v5.*;
import org.mockserver.authentication.ConstantTimeEquals;
import org.mockserver.authentication.ProxyAuthenticationContext;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static org.mockserver.authentication.ProxyAuthenticationValidator.proxyAuthenticationConfigured;
import static org.mockserver.log.model.LogEntry.LogMessageType.AUTHENTICATION_FAILED;

@ChannelHandler.Sharable
public class Socks5ProxyHandler extends SocksProxyHandler<Socks5Message> {

    public Socks5ProxyHandler(Configuration configuration, MockServerLogger mockServerLogger, LifeCycle server) {
        super(configuration, mockServerLogger, server);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, Socks5Message socksRequest) {
        if (socksRequest instanceof Socks5InitialRequest) {
            handleInitialRequest(ctx, (Socks5InitialRequest) socksRequest);
        } else if (socksRequest instanceof Socks5PasswordAuthRequest) {
            handlePasswordAuthRequest(ctx, (Socks5PasswordAuthRequest) socksRequest);
        } else if (socksRequest instanceof Socks5CommandRequest) {
            handleCommandRequest(ctx, (Socks5CommandRequest) socksRequest);
        } else {
            ctx.close();
        }
    }

    private void handleInitialRequest(ChannelHandlerContext ctx, Socks5InitialRequest initialRequest) {
        boolean credentialsRequired = proxyAuthenticationConfigured(configuration.proxyAuthenticationUsername(), configuration.proxyAuthenticationPassword());
        if (credentialsRequired && initialRequest.authMethods().contains(Socks5AuthMethod.PASSWORD)) {
            acceptInitialRequest(ctx, Socks5AuthMethod.PASSWORD, new Socks5PasswordAuthRequestDecoder());
        } else if (!credentialsRequired && initialRequest.authMethods().contains(Socks5AuthMethod.NO_AUTH)) {
            acceptInitialRequest(ctx, Socks5AuthMethod.NO_AUTH, new Socks5CommandRequestDecoder());
        } else {
            // with credentials configured, a client that does not offer username/password (RFC 1929) is refused,
            // even when it offers NO_AUTH
            if (credentialsRequired && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(AUTHENTICATION_FAILED)
                        .setLogLevel(Level.INFO)
                        .setMessageFormat("proxy authentication is required but SOCKS5 client from:{}did not offer username/password authentication so returning NO ACCEPTABLE METHODS response")
                        .setArguments(ctx.channel().remoteAddress())
                );
            }
            refuse(ctx, new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED));
            ctx.pipeline().remove(Socks5InitialRequestDecoder.class);
        }
    }

    private void acceptInitialRequest(ChannelHandlerContext ctx, Socks5AuthMethod authMethod, ChannelHandler nextRequestDecoder) {
        ctx.writeAndFlush(new DefaultSocks5InitialResponse(authMethod));
        // replaced in place, not removed with the next decoder added in front, so bytes the client sent after its
        // greeting go to the next decoder rather than past it to the protocol detection behind this handler
        ctx.pipeline().replace(Socks5InitialRequestDecoder.class, null, nextRequestDecoder);
    }

    private void handlePasswordAuthRequest(ChannelHandlerContext ctx, Socks5PasswordAuthRequest passwordAuthRequest) {
        String username = configuration.proxyAuthenticationUsername();
        String password = configuration.proxyAuthenticationPassword();
        // we need the null-check again here, in case the properties got unset between init and auth request.
        // Credentials are compared in constant time (ConstantTimeEquals) so an attacker cannot recover the
        // configured username/password one byte at a time by measuring how long the comparison takes.
        if (proxyAuthenticationConfigured(username, password)
            // non-short-circuit &, so the time taken does not show whether the username alone was right
            && (ConstantTimeEquals.equals(username, passwordAuthRequest.username()) & ConstantTimeEquals.equals(password, passwordAuthRequest.password()))) {
            ctx.channel().attr(HttpRequestHandler.PROXY_AUTHENTICATION_CONTEXT).set(new ProxyAuthenticationContext(username));
            // the reply is written first: a command sent in the same read is decoded as soon as the decoder is replaced
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
            ctx.pipeline().replace(Socks5PasswordAuthRequestDecoder.class, null, new Socks5CommandRequestDecoder());
        } else {
            refuse(ctx, new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE));
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(AUTHENTICATION_FAILED)
                        .setLogLevel(Level.INFO)
                        .setMessageFormat("proxy authentication failed for SOCKS5 client from:{}so returning SOCKS FAILURE response")
                        .setArguments(ctx.channel().remoteAddress())
                );
            }
        }
    }

    private void handleCommandRequest(ChannelHandlerContext ctx, final Socks5CommandRequest commandRequest) {
        if (commandRequest.type().equals(Socks5CommandType.CONNECT)) {
            forwardConnection(ctx, new Socks5ConnectHandler(configuration, mockServerLogger, server, commandRequest.dstAddr(), commandRequest.dstPort()), commandRequest.dstAddr(), commandRequest.dstPort());
            ctx.fireChannelRead(commandRequest);
        } else {
            ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, Socks5AddressType.DOMAIN, "", 0)).addListener(ChannelFutureListener.CLOSE);
        }
    }
}

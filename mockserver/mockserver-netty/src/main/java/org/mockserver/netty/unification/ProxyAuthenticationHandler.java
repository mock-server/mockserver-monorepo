package org.mockserver.netty.unification;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.text.StringEscapeUtils;
import org.mockserver.authentication.ProxyAuthenticationValidator;
import org.mockserver.authentication.ProxyAuthenticationContext;
import org.mockserver.configuration.Configuration;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.controlplane.ListenerRole;
import org.mockserver.netty.proxy.relay.RelayLoopbackAddresses;

import java.util.Collections;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED;
import static org.mockserver.netty.HttpRequestHandler.*;

/** Authenticate before any request consumer sees proxy credentials. */
public final class ProxyAuthenticationHandler extends ChannelInboundHandlerAdapter {
    private final Configuration configuration;

    public ProxyAuthenticationHandler(Configuration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        org.mockserver.model.HttpRequest request = null;
        String username = configuration.proxyAuthenticationUsername();
        String password = configuration.proxyAuthenticationPassword();
        boolean headers = message instanceof HttpRequest
            || message instanceof Http2HeadersFrame frame && frame.headers().method() != null;
        boolean tunnel = false;
        if (Boolean.TRUE.equals(ctx.channel().attr(PROXYING).get())) {
            for (Channel channel = ctx.channel(); channel != null; channel = channel.parent()) {
                if (RelayLoopbackAddresses.sourceIdentityOf(channel) != null) {
                    tunnel = true;
                    break;
                }
            }
        }
        ListenerRole role = ListenerRole.of(ctx.channel());
        if (headers && !tunnel) {
            ctx.channel().attr(PROXY_AUTHENTICATION_CONTEXT).set(null);
        }
        boolean check = headers && !tunnel && role != ListenerRole.MANAGEMENT
            && ProxyAuthenticationValidator.proxyAuthenticationConfigured(username, password);
        if (message instanceof HttpRequest incoming) {
            if (check) {
                request = org.mockserver.model.HttpRequest.request().withMethod(incoming.method().name()).withPath(incoming.uri())
                    .withHeader(HOST.toString(), incoming.headers().getAll(HOST).toArray(String[]::new))
                    .withHeader(PROXY_AUTHORIZATION.toString(), incoming.headers().getAll(PROXY_AUTHORIZATION).toArray(String[]::new));
            }
            incoming.headers().remove(PROXY_AUTHORIZATION);
        } else if (message instanceof Http2HeadersFrame frame) {
            if (check) {
                request = org.mockserver.model.HttpRequest.request().withMethod(frame.headers().method().toString())
                    .withPath(frame.headers().path() == null ? "" : frame.headers().path().toString());
                if (frame.headers().authority() != null) {
                    request.withHeader(HOST.toString(), frame.headers().authority().toString());
                }
                request.withHeader(PROXY_AUTHORIZATION.toString(), frame.headers().getAll(PROXY_AUTHORIZATION)
                    .stream().map(CharSequence::toString).toArray(String[]::new));
            }
            for (CharSequence name : frame.headers().names()) {
                if (AsciiString.contentEqualsIgnoreCase(PROXY_AUTHORIZATION, name)) {
                    frame.headers().remove(name);
                }
            }
        }
        if (request != null) {
            Set<String> local = ctx.channel().attr(LOCAL_HOST_HEADERS).get();
            boolean management = role == ListenerRole.COMBINED
                && HttpState.isControlPlaneRequestTarget(request.getMethod().getValue(), request.getPath().getValue());
            boolean required = !management && ("CONNECT".equals(request.getMethod().getValue())
                || HttpActionHandler.potentiallyHttpProxy(configuration, request, local == null ? Collections.emptySet() : local,
                    role == ListenerRole.COMBINED && Boolean.TRUE.equals(ctx.channel().attr(PROXYING).get()),
                    role == ListenerRole.COMBINED));
            if (required && !ProxyAuthenticationValidator.isAuthenticated(request, username, password)) {
                String challenge = "Basic realm=\"" + StringEscapeUtils.escapeJava(configuration.proxyAuthenticationRealm()) + "\", charset=\"UTF-8\"";
                Object response;
                if (message instanceof HttpRequest incoming) {
                    DefaultFullHttpResponse http = new DefaultFullHttpResponse(incoming.protocolVersion(), PROXY_AUTHENTICATION_REQUIRED);
                    http.headers().setInt(CONTENT_LENGTH, 0).set(PROXY_AUTHENTICATE, challenge);
                    response = http;
                } else {
                    response = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("407")
                        .set(PROXY_AUTHENTICATE, challenge), true);
                }
                ReferenceCountUtil.release(message);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (required) {
                ctx.channel().attr(PROXY_AUTHENTICATION_CONTEXT).set(new ProxyAuthenticationContext(username));
            }
        }
        ctx.fireChannelRead(message);
    }
}

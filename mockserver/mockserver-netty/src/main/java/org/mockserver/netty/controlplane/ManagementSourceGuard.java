package org.mockserver.netty.controlplane;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.Headers;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.mock.HttpState;
import org.mockserver.url.URLParser;

import java.util.Map;

/** Checks management source identity before shortcuts, upgrades and protocol-specific dispatch. */
public final class ManagementSourceGuard extends ChannelInboundHandlerAdapter {
    private final ControlPlaneListenerSettings settings;
    private final HttpState httpState;
    private boolean denied;

    public ManagementSourceGuard(ControlPlaneListenerSettings settings, HttpState httpState) {
        this.settings = settings;
        this.httpState = httpState;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        if (denied) {
            ReferenceCountUtil.release(message);
            return;
        }
        String method;
        String target;
        String preflight;
        if (message instanceof HttpRequest request) {
            method = request.method().name();
            target = request.uri();
            preflight = "OPTIONS".equals(method) && request.headers().contains("origin")
                ? request.headers().get("access-control-request-method") : null;
        } else if (message instanceof Http2HeadersFrame frame && frame.headers().method() != null) {
            method = frame.headers().method().toString();
            target = frame.headers().path() == null ? null : frame.headers().path().toString();
            preflight = "OPTIONS".equals(method) && firstHeader(frame.headers(), "origin") != null
                ? firstHeader(frame.headers(), "access-control-request-method") : null;
        } else if (message instanceof Http3HeadersFrame frame && frame.headers().method() != null) {
            method = frame.headers().method().toString();
            target = frame.headers().path() == null ? null : frame.headers().path().toString();
            preflight = "OPTIONS".equals(method) && firstHeader(frame.headers(), "origin") != null
                ? firstHeader(frame.headers(), "access-control-request-method") : null;
        } else {
            ctx.fireChannelRead(message);
            return;
        }
        // TCP's mapper uses returnPath; HTTP/3's bridge strips the query without decoding or resolving an authority.
        String path = URLParser.returnPath(target);
        if (message instanceof Http3HeadersFrame) {
            int query = target == null ? -1 : target.indexOf('?');
            path = query < 0 ? target : target.substring(0, query);
            if (path == null || path.isEmpty()) {
                path = "/";
            }
        }
        if (settings == null || ListenerRole.of(ctx.channel()) == ListenerRole.PROXY
            || !httpState.isControlPlaneRequest("OPTIONS".equals(method) && preflight != null ? preflight : method, path)
            || settings.allowed(settings.source(ctx.channel()))) {
            ctx.fireChannelRead(message);
            return;
        }
        denied = true;
        if (message instanceof HttpRequest request) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.FORBIDDEN);
            response.headers().setInt("Content-Length", 0);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else if (message instanceof Http2HeadersFrame) {
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("403"), true))
                .addListener(ChannelFutureListener.CLOSE);
        } else {
            DefaultHttp3HeadersFrame response = new DefaultHttp3HeadersFrame();
            response.headers().status("403");
            ctx.writeAndFlush(response).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }
        ReferenceCountUtil.release(message);
    }

    private static String firstHeader(Headers<CharSequence, CharSequence, ?> headers, String name) {
        for (Map.Entry<CharSequence, CharSequence> entry : headers) {
            if (AsciiString.contentEqualsIgnoreCase(name, entry.getKey())) {
                return entry.getValue().toString();
            }
        }
        return null;
    }
}

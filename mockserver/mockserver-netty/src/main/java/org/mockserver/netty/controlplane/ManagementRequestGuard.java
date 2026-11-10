package org.mockserver.netty.controlplane;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/** A management listener never creates a forwarding tunnel or accepts proxy request targets. */
public final class ManagementRequestGuard extends SimpleChannelInboundHandler<FullHttpRequest> {
    public ManagementRequestGuard() {
        super(false);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (HttpMethod.CONNECT.equals(request.method()) || !request.uri().startsWith("/")) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.METHOD_NOT_ALLOWED);
            response.headers().setInt("Content-Length", 0);
            io.netty.util.ReferenceCountUtil.release(request);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.fireChannelRead(request);
        }
    }
}

package org.mockserver.netty.controlplane;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/** Rejects management connections outside the configured source networks. */
public final class ControlPlaneAccessHandler extends ChannelInboundHandlerAdapter {
    private final ControlPlaneListenerSettings settings;

    public ControlPlaneAccessHandler(ControlPlaneListenerSettings settings) {
        this.settings = settings;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (settings.allowed(settings.source(ctx.channel()))) {
            ctx.fireChannelActive();
        } else {
            ctx.close();
        }
    }
}

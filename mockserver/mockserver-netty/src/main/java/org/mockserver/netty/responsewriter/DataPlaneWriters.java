package org.mockserver.netty.responsewriter;

import io.netty.channel.ChannelHandlerContext;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.netty.InFlightRequest;

/**
 * Builds the Netty writers used when an extension request filter or response transformer is loaded. Called only once
 * {@code HttpState.dataPlaneHooks()} is known to be non-null, and every factory returns the base writer type, so a
 * handler that builds a writer never needs the transforming classes unless one is loaded.
 */
public final class DataPlaneWriters {

    private DataPlaneWriters() {
    }

    public static NettyResponseWriter netty(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx, HttpState httpState, InFlightRequest inFlightRequest) {
        return new TransformingNettyResponseWriter(configuration, mockServerLogger, ctx, httpState.getScheduler(), inFlightRequest, httpState.dataPlaneHooks());
    }
}

package org.mockserver.netty.responsewriter;

import io.netty.channel.ChannelHandlerContext;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.netty.InFlightRequest;
import org.mockserver.netty.http3.Http3GrpcResponseWriter;
import org.mockserver.netty.http3.Http3ResponseWriter;
import org.mockserver.netty.http3.TransformingHttp3GrpcResponseWriter;
import org.mockserver.netty.http3.TransformingHttp3ResponseWriter;

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

    public static Http3ResponseWriter http3(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx, HttpState httpState) {
        return new TransformingHttp3ResponseWriter(configuration, mockServerLogger, ctx, httpState.getScheduler(), httpState.dataPlaneHooks());
    }

    public static Http3GrpcResponseWriter http3Grpc(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx, HttpState httpState, GrpcProtoDescriptorStore descriptorStore, String grpcService, String grpcMethod) {
        return new TransformingHttp3GrpcResponseWriter(configuration, mockServerLogger, ctx, descriptorStore, grpcService, grpcMethod, httpState.getWebSocketClientRegistry(), httpState.getScheduler(), httpState.dataPlaneHooks());
    }
}

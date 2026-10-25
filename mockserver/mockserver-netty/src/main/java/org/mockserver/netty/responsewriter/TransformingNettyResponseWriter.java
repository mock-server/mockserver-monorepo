package org.mockserver.netty.responsewriter;

import io.netty.channel.ChannelHandlerContext;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.InFlightRequest;
import org.mockserver.responsewriter.TransformingWrites;
import org.mockserver.responsewriter.TransformingWriter;
import org.mockserver.scheduler.Scheduler;

/**
 * The HTTP/1.1 and HTTP/2 writer {@link DataPlaneWriters} builds when an extension request filter or response
 * transformer is loaded: a {@link NettyResponseWriter} whose data-plane writes follow {@link TransformingWrites}.
 * A write it hands on runs {@link NettyResponseWriter}'s own {@code writeResponse}, possibly on an extension pool
 * thread, and Netty moves the I/O to the channel's event loop.
 */
public class TransformingNettyResponseWriter extends NettyResponseWriter implements TransformingWriter {

    private final TransformingWrites writes;

    TransformingNettyResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Scheduler scheduler, InFlightRequest inFlightRequest, DataPlaneHooks hooks) {
        super(configuration, mockServerLogger, ctx, scheduler, inFlightRequest);
        this.writes = new TransformingWrites(hooks, scheduler, mockServerLogger, this::writeWithoutHooks, () -> isProxying(ctx));
    }

    private static boolean isProxying(ChannelHandlerContext ctx) {
        return ctx != null && Boolean.TRUE.equals(ctx.channel().attr(HttpRequestHandler.PROXYING).get());
    }

    private void writeWithoutHooks(HttpRequest request, HttpResponse response, boolean apiResponse) {
        super.writeResponse(request, response, apiResponse);
    }

    @Override
    public void writeResponse(HttpRequest request, HttpResponse response, boolean apiResponse) {
        writes.writeResponse(request, response, apiResponse);
    }

    @Override
    public void writeDataPlanePreflightResponse(HttpRequest request) {
        writes.untransformed(() -> super.writeDataPlanePreflightResponse(request));
    }

    @Override
    public void markDataPlane(HttpRequest request) {
        writes.markDataPlane(request);
    }

    @Override
    public void markGenerated() {
        writes.markGenerated();
    }

    @Override
    public boolean isClaimed() {
        return writes.isClaimed();
    }

    @Override
    public void writeUntransformed(HttpRequest request, HttpResponse response) {
        writes.writeUntransformed(request, response);
    }

    @Override
    public void failUnanswered(Throwable throwable) {
        writes.failUnanswered(throwable);
    }
}

package org.mockserver.netty.http3;

import io.netty.channel.ChannelHandlerContext;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.TransformingWrites;
import org.mockserver.responsewriter.TransformingWriter;
import org.mockserver.scheduler.Scheduler;

/**
 * The gRPC-over-HTTP/3 writer built when an extension request filter or response transformer is loaded: an
 * {@link Http3GrpcResponseWriter} whose data-plane writes follow {@link TransformingWrites}, so the response
 * transformers see the JSON response before it is framed as protobuf. An error status written through
 * {@link #writeErrorResponseIfUnanswered} and an elapsed {@code grpc-timeout} each claim the exchange too: the
 * deadline's {@code DEADLINE_EXCEEDED} wins over a response the transformers are still working on, which the
 * stream's own once-guard then drops.
 */
public class TransformingHttp3GrpcResponseWriter extends Http3GrpcResponseWriter implements TransformingWriter {

    private final ChannelHandlerContext ctx;
    private final TransformingWrites writes;

    public TransformingHttp3GrpcResponseWriter(
        Configuration configuration,
        MockServerLogger mockServerLogger,
        ChannelHandlerContext ctx,
        GrpcProtoDescriptorStore descriptorStore,
        String grpcService,
        String grpcMethod,
        WebSocketClientRegistry webSocketClientRegistry,
        Scheduler scheduler,
        DataPlaneHooks hooks
    ) {
        super(configuration, mockServerLogger, ctx, descriptorStore, grpcService, grpcMethod, webSocketClientRegistry);
        this.ctx = ctx;
        this.writes = new TransformingWrites(hooks, scheduler, mockServerLogger, this::writeWithoutHooks, () -> false);
    }

    private void writeWithoutHooks(HttpRequest request, HttpResponse response, boolean apiResponse) {
        super.writeResponse(request, response, apiResponse);
    }

    @Override
    boolean claimForDeadline() {
        if (!super.claimForDeadline()) {
            return false;
        }
        // a final response made after this is dropped at once, without running the transformers
        writes.claimForFailure();
        return true;
    }

    @Override
    void writeErrorResponseIfUnanswered(GrpcStatusMapper.GrpcStatusCode statusCode, String message) {
        if (writes.claimForFailure()) {
            writeErrorOnStreamLoop(statusCode, message);
        }
    }

    // the stream state and deadline the base method touches belong to the stream's event loop
    private void writeErrorOnStreamLoop(GrpcStatusMapper.GrpcStatusCode statusCode, String message) {
        if (ctx.executor().inEventLoop()) {
            super.writeErrorResponseIfUnanswered(statusCode, message);
        } else {
            Http3ResponseWriter.executeOnStreamEventLoop(ctx, mockServerLogger, () -> super.writeErrorResponseIfUnanswered(statusCode, message));
        }
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

    /**
     * Answers an unanswered exchange with {@code INTERNAL}, as the handler's own failure path does over HTTP/3.
     */
    @Override
    public void failUnanswered(Throwable throwable) {
        writes.failUnanswered(throwable, (request, message) -> writeErrorOnStreamLoop(GrpcStatusMapper.GrpcStatusCode.INTERNAL, message));
    }
}

package org.mockserver.netty.http3;

import io.netty.channel.ChannelHandlerContext;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ControlPlaneFailureResponse;
import org.mockserver.responsewriter.TransformingWrites;
import org.mockserver.responsewriter.TransformingWriter;
import org.mockserver.scheduler.Scheduler;

/**
 * The HTTP/3 writer built when an extension request filter or response transformer is loaded: an
 * {@link Http3ResponseWriter} whose data-plane writes follow {@link TransformingWrites}. A write it hands on runs
 * {@link Http3ResponseWriter}'s own {@code writeResponse}, possibly on an extension pool thread, and
 * {@code sendResponse} moves it to the stream's event loop. A claimed exchange counts as a started response, so the
 * handler's failure path never writes a second one.
 */
public class TransformingHttp3ResponseWriter extends Http3ResponseWriter implements TransformingWriter {

    private final TransformingWrites writes;

    public TransformingHttp3ResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Scheduler scheduler, DataPlaneHooks hooks) {
        super(configuration, mockServerLogger, ctx);
        this.writes = new TransformingWrites(hooks, scheduler, mockServerLogger, this::writeWithoutHooks, () -> false);
    }

    private void writeWithoutHooks(HttpRequest request, HttpResponse response, boolean apiResponse) {
        super.writeResponse(request, response, apiResponse);
    }

    @Override
    boolean isResponseStarted() {
        return writes.isClaimed() || super.isResponseStarted();
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
     * Claims the exchange, and writes the failure response unless a response the writer did not see (a stream reset,
     * an SSE or other directly written response) has already started on the stream.
     */
    @Override
    public void failUnanswered(Throwable throwable) {
        writes.failUnanswered(throwable, (request, message) -> {
            if (!super.isResponseStarted()) {
                writeWithoutHooks(request, ControlPlaneFailureResponse.dataPlaneFailureResponse(message), false);
            }
        });
    }
}

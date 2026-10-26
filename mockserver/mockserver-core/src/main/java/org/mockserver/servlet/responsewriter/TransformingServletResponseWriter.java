package org.mockserver.servlet.responsewriter;

import jakarta.servlet.http.HttpServletResponse;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.TransformingWrites;
import org.mockserver.responsewriter.TransformingWriter;
import org.mockserver.scheduler.Scheduler;

/**
 * The servlet writer {@link CoreDataPlaneWriters} builds when an extension request filter or response transformer is
 * loaded: a {@link ServletResponseWriter} whose data-plane writes follow {@link TransformingWrites}. A servlet answers
 * on the container's request thread, so that thread waits, for at most the budget, for the response transformers and
 * then calls {@code sendResponse} itself; the transformers still run on the extension pool.
 */
public class TransformingServletResponseWriter extends ServletResponseWriter implements TransformingWriter {

    private final TransformingWrites writes;

    TransformingServletResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, HttpServletResponse httpServletResponse, Scheduler scheduler, DataPlaneHooks hooks, MockServerLogger eventLogger, boolean proxyRequest) {
        super(configuration, mockServerLogger, httpServletResponse);
        this.writes = new TransformingWrites(hooks, scheduler, eventLogger, this::writeWithoutHooks, () -> proxyRequest, true);
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

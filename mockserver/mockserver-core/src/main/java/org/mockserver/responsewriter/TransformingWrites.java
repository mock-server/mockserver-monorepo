package org.mockserver.responsewriter;

import org.mockserver.cors.CORSHeaders;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.extension.DataPlaneOutcome;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.TransformContext;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

import static org.mockserver.log.model.LogEntry.LogMessageType.INFO;
import static org.mockserver.log.model.LogEntry.LogMessageType.WARN;

/**
 * The write rules every {@link TransformingWriter} shares, whatever its transport: the claim, dropping later final
 * responses, the order writes reach the wire, and running the response transformer chain on the extension pool.
 * A transport's writer passes each write here, and this passes it back, possibly from another thread, to the
 * transport's own {@code writeResponse} (which then adds CORS, the default headers and the {@code Connection} header,
 * so a transformer never sees or removes them).
 */
public final class TransformingWrites {

    /**
     * The transport writer's own {@code writeResponse}, as it is with no extension loaded.
     */
    @FunctionalInterface
    public interface BaseWrite {
        void write(HttpRequest request, HttpResponse response, boolean apiResponse);
    }

    private static final int UNCLAIMED = 0;
    private static final int ANSWERED = 1;
    private static final int ANSWERED_WITH_FAILURE = 2;

    private final DataPlaneHooks hooks;
    private final Scheduler scheduler;
    private final MockServerLogger mockServerLogger;
    private final BaseWrite baseWrite;
    private final BooleanSupplier proxyRequest;
    private final boolean waitOnCaller;
    private final AtomicInteger claim = new AtomicInteger(UNCLAIMED);
    private volatile HttpRequest dataPlaneRequest;
    private volatile boolean generated;
    private volatile Thread writingUntransformed;
    // guarded by this: completes once the last write made so far has been handed to the transport
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    /**
     * @param proxyRequest whether the exchange's request was sent to MockServer as a proxy, read when its response
     *                     is transformed
     */
    public TransformingWrites(DataPlaneHooks hooks, Scheduler scheduler, MockServerLogger mockServerLogger, BaseWrite baseWrite, BooleanSupplier proxyRequest) {
        this(hooks, scheduler, mockServerLogger, baseWrite, proxyRequest, false);
    }

    /**
     * @param waitOnCaller true for a transport that answers on the thread that writes (a servlet,
     *                     {@code InProcessMockServer.handle}): that thread waits, for at most the budget, for the
     *                     transformer chain and then makes the write itself
     */
    public TransformingWrites(DataPlaneHooks hooks, Scheduler scheduler, MockServerLogger mockServerLogger, BaseWrite baseWrite, BooleanSupplier proxyRequest, boolean waitOnCaller) {
        this.hooks = hooks;
        this.scheduler = scheduler;
        this.mockServerLogger = mockServerLogger;
        this.baseWrite = baseWrite;
        this.proxyRequest = proxyRequest;
        this.waitOnCaller = waitOnCaller;
    }

    public void markDataPlane(HttpRequest request) {
        dataPlaneRequest = request;
    }

    public void markGenerated() {
        generated = true;
    }

    public boolean isClaimed() {
        return claim.get() != UNCLAIMED;
    }

    /**
     * A transport writer's {@code writeResponse}: before {@link #markDataPlane} it writes as with no extension
     * loaded; after, a final response claims the exchange (or is dropped), an API response or a write made inside
     * {@link #untransformed} is written as it is, any other final response goes through the transformer chain, and an
     * interim ({@code 1xx}) response is written as it is without claiming.
     */
    public void writeResponse(HttpRequest request, HttpResponse response, boolean apiResponse) {
        if (dataPlaneRequest == null) {
            baseWrite.write(request, response, apiResponse);
            return;
        }
        HttpResponse toWrite = response != null ? response : HttpResponse.notFoundResponse();
        if (!isFinal(toWrite)) {
            enqueue(CompletableFuture.completedFuture(() -> baseWrite.write(request, toWrite, apiResponse)), null);
            return;
        }
        boolean wasGenerated = generated || response == null;
        generated = false;
        if (!claim(ANSWERED, request, toWrite)) {
            return;
        }
        if (apiResponse || writingUntransformed == Thread.currentThread() || !hooks.hasResponseTransformers()) {
            enqueue(CompletableFuture.completedFuture(() -> baseWrite.write(request, toWrite, apiResponse)), request);
            return;
        }
        CompletableFuture<Runnable> ready = new CompletableFuture<>();
        enqueue(ready, request);
        StreamingBody streamingBody = toWrite.getStreamingBody();
        try {
            TransformContext context = TransformContext.of(filterContext(request), streamingBody != null, toWrite.getExpectationId(), wasGenerated);
            if (waitOnCaller) {
                // every earlier write was made on this thread, so completing ready writes here, on the caller
                DataPlaneOutcome outcome = hooks.transformAndWait(request, toWrite, context, scheduler);
                ready.complete(() -> writeOutcome(request, toWrite, outcome));
            } else {
                hooks.transform(request, toWrite, context, scheduler, outcome -> ready.complete(() -> writeOutcome(request, toWrite, outcome)));
            }
        } catch (Throwable throwable) {
            String message = ControlPlaneFailureResponse.logUnexpectedFailure(mockServerLogger, request, throwable);
            ready.complete(() -> writeFailure(request, toWrite, ControlPlaneFailureResponse.dataPlaneFailureResponse(message)));
        }
    }

    /**
     * Runs {@code write}, during which every response this thread writes through the writer is written untransformed,
     * as {@link #writeUntransformed} does: for a write the transport's base writer builds and then writes through its
     * own {@code writeResponse}, such as the data-plane CORS preflight.
     */
    public void untransformed(Runnable write) {
        writingUntransformed = Thread.currentThread();
        try {
            write.run();
        } finally {
            writingUntransformed = null;
        }
    }

    public void writeUntransformed(HttpRequest request, HttpResponse response) {
        if (!isFinal(response)) {
            enqueue(CompletableFuture.completedFuture(() -> baseWrite.write(request, response, false)), null);
        } else if (claim(ANSWERED, request, response)) {
            enqueue(CompletableFuture.completedFuture(() -> baseWrite.write(request, response, false)), request);
        }
    }

    /**
     * Answers with the data-plane failure response unless the exchange is claimed. It needs the request
     * {@link #markDataPlane} recorded; without it the failure is only logged.
     */
    public void failUnanswered(Throwable throwable) {
        failUnanswered(throwable, null);
    }

    /**
     * As {@link #failUnanswered(Throwable)}, but a transport whose failure is not an HTTP response (a gRPC status)
     * answers through {@code answer}, given the request and the logged failure message, once this has claimed the
     * exchange.
     */
    public void failUnanswered(Throwable throwable, BiConsumer<HttpRequest, String> answer) {
        HttpRequest request = dataPlaneRequest;
        String message = ControlPlaneFailureResponse.logUnexpectedFailure(mockServerLogger, request, throwable);
        if (request != null && claim.compareAndSet(UNCLAIMED, ANSWERED_WITH_FAILURE)) {
            if (answer != null) {
                enqueue(CompletableFuture.completedFuture(() -> answer.accept(request, message)), null);
            } else {
                HttpResponse failure = ControlPlaneFailureResponse.dataPlaneFailureResponse(message);
                enqueue(CompletableFuture.completedFuture(() -> baseWrite.write(request, failure, false)), null);
            }
        }
    }

    /**
     * Claims the exchange for a failure the transport writes itself (a gRPC status, an elapsed gRPC deadline), so
     * any later final response is dropped without running the transformers.
     *
     * @return false when the exchange was already claimed
     */
    public boolean claimForFailure() {
        return claim.compareAndSet(UNCLAIMED, ANSWERED_WITH_FAILURE);
    }

    private void writeOutcome(HttpRequest request, HttpResponse original, DataPlaneOutcome outcome) {
        if (outcome.isFailure()) {
            writeFailure(request, original, outcome.response());
            return;
        }
        HttpResponse transformed = outcome.response();
        if (!outcome.changedBy().isEmpty()) {
            logTransformed(request, transformed, outcome.changedBy());
        }
        // the transport adds headers to what it writes, here off the caller's thread, while a streamed response's
        // completion listener may already be cloning the caller's object for the event log
        boolean callersStreamedResponse = transformed == original && original.getStreamingBody() != null;
        baseWrite.write(request, callersStreamedResponse ? original.cloneWithHeaders() : transformed, false);
    }

    private void writeFailure(HttpRequest request, HttpResponse original, HttpResponse failure) {
        claim.set(ANSWERED_WITH_FAILURE);
        closeUpstream(original);
        baseWrite.write(request, failure, false);
    }

    private boolean claim(int answer, HttpRequest request, HttpResponse response) {
        if (claim.compareAndSet(UNCLAIMED, answer)) {
            return true;
        }
        closeUpstream(response);
        hooks.droppedWrite(claim.get() == ANSWERED_WITH_FAILURE ? DataPlaneHooks.DROPPED_AFTER_FAILURE : DataPlaneHooks.DROPPED_DUPLICATE, request, response);
        return false;
    }

    // a streamed response that will never be written must not keep its upstream open
    private static void closeUpstream(HttpResponse response) {
        StreamingBody streamingBody = response != null ? response.getStreamingBody() : null;
        if (streamingBody != null) {
            streamingBody.closeUpstream();
        }
    }

    /**
     * Hands {@code ready}'s write to the transport once it is ready and every earlier write has been handed over, on
     * whichever thread completes last.
     *
     * @param answering the request of a final response that has claimed the exchange, which is answered with the
     *                  data-plane failure response if the write throws, as {@code HttpRequestHandler} does with no
     *                  extension loaded; {@code null} for an interim or failure response
     */
    private void enqueue(CompletableFuture<Runnable> ready, HttpRequest answering) {
        CompletableFuture<Void> written = new CompletableFuture<>();
        CompletableFuture<Void> previous;
        synchronized (this) {
            previous = tail;
            tail = written;
        }
        previous.thenCombine(ready, (ignored, write) -> write).whenComplete((write, error) -> {
            try {
                if (write != null) {
                    write.run();
                }
            } catch (Throwable throwable) {
                answerFailedWrite(answering, throwable);
            } finally {
                written.complete(null);
            }
        });
    }

    private FilterContext filterContext(HttpRequest request) {
        String contentType = request.getFirstHeader("content-type");
        return FilterContext.builder()
            .correlationId(request.getLogCorrelationId())
            .protocol(request.getProtocol())
            .secure(Boolean.TRUE.equals(request.isSecure()))
            .remoteAddress(request.getRemoteAddress())
            .proxyRequest(proxyRequest.getAsBoolean())
            .corsPreflight(CORSHeaders.isCorsPreflight(request))
            .grpc(contentType != null && contentType.startsWith("application/grpc"))
            .build();
    }

    private static boolean isFinal(HttpResponse response) {
        Integer statusCode = response.getStatusCode();
        return statusCode == null || statusCode >= 200;
    }

    private void logTransformed(HttpRequest request, HttpResponse sent, List<String> changedBy) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(new LogEntry()
                .setType(INFO)
                .setLogLevel(Level.INFO)
                .setCorrelationId(request.getLogCorrelationId())
                .setHttpRequest(request)
                .setHttpResponse(sent)
                .setMessageFormat("response transformed by:{}and the response sent was:{}")
                .setArguments(changedBy, sent));
        }
    }

    private void answerFailedWrite(HttpRequest answering, Throwable throwable) {
        if (answering == null) {
            logWriteFailure(throwable);
            return;
        }
        claim.set(ANSWERED_WITH_FAILURE);
        String message = ControlPlaneFailureResponse.logUnexpectedFailure(mockServerLogger, answering, throwable);
        try {
            baseWrite.write(answering, ControlPlaneFailureResponse.dataPlaneFailureResponse(message), false);
        } catch (Throwable failureWriteFailed) {
            logWriteFailure(failureWriteFailed);
        }
    }

    private void logWriteFailure(Throwable throwable) {
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(new LogEntry()
                .setType(WARN)
                .setLogLevel(Level.WARN)
                .setMessageFormat("failed to write a response after the extension response transformers")
                .setThrowable(throwable));
        }
    }
}

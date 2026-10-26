package org.mockserver.responsewriter;

import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

/**
 * A response writer built only when an extension request filter or response transformer is loaded: each transport's
 * {@code Transforming*ResponseWriter}. Once its exchange reaches the data plane ({@link #markDataPlane}), the first
 * final ({@code >= 200}) response written claims the exchange, any later final response is dropped with a
 * {@code WARN} and the {@code mock_server_extension_dataplane_dropped_writes} counter, and writes reach the wire in
 * the order they were made. {@code writeResponse} runs the response transformer chain; the methods here write
 * without it.
 * <p>
 * Nothing on the request path may name this type unless {@code HttpState.dataPlaneHooks()} is non-null, so a server
 * with no such extension never loads it.
 */
public interface TransformingWriter {

    /**
     * Marks the exchange as data-plane work. Writes made before it (control-plane responses, data-plane
     * authentication and TLS refusals) pass through as they would with no extension loaded, untransformed and
     * unclaimed.
     *
     * @param request the request the exchange answers, for {@link #failUnanswered}
     */
    void markDataPlane(HttpRequest request);

    /**
     * Marks the next final response as made by MockServer itself (a no-match {@code 404}, an overload {@code 503}, a
     * rate-limit {@code 429}; see {@code TransformContext.isGenerated()} for the full list), which the response
     * transformers see as {@code TransformContext.isGenerated()}.
     */
    void markGenerated();

    /**
     * @return true once a final response has claimed the exchange
     */
    boolean isClaimed();

    /**
     * Writes a response without the response transformers, claiming the exchange when it is final, or dropping it
     * when the exchange was already answered.
     */
    void writeUntransformed(HttpRequest request, HttpResponse response);

    /**
     * Answers the exchange with the data-plane failure response ({@code 500}, untransformed) unless it is already
     * claimed; the failure is logged either way. Needs the request {@link #markDataPlane} recorded: without it nothing
     * can be written, so call the static {@link #failUnanswered(MockServerLogger, ResponseWriter, HttpRequest, Throwable)},
     * which marks the exchange first.
     */
    void failUnanswered(Throwable throwable);

    /**
     * Calls {@link #markDataPlane} when {@code responseWriter} is a transforming writer.
     */
    static void markDataPlane(ResponseWriter responseWriter, HttpRequest request) {
        if (responseWriter instanceof TransformingWriter transformingWriter) {
            transformingWriter.markDataPlane(request);
        }
    }

    /**
     * Calls {@link #markGenerated} when {@code responseWriter} is a transforming writer.
     */
    static void markGenerated(ResponseWriter responseWriter) {
        if (responseWriter instanceof TransformingWriter transformingWriter) {
            transformingWriter.markGenerated();
        }
    }

    /**
     * Marks the exchange as data-plane work for {@code request} and calls {@link #failUnanswered} when
     * {@code responseWriter} is a transforming writer; otherwise answers as a writer with no extension loaded does,
     * with the data-plane failure response.
     */
    static void failUnanswered(MockServerLogger mockServerLogger, ResponseWriter responseWriter, HttpRequest request, Throwable throwable) {
        if (responseWriter instanceof TransformingWriter transformingWriter) {
            transformingWriter.markDataPlane(request);
            transformingWriter.failUnanswered(throwable);
        } else {
            String message = ControlPlaneFailureResponse.logUnexpectedFailure(mockServerLogger, request, throwable);
            responseWriter.writeResponse(request, ControlPlaneFailureResponse.dataPlaneFailureResponse(message), false);
        }
    }
}

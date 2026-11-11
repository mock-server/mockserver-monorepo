package org.mockserver.responsewriter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamWriteException;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import org.mockserver.authentication.ProxyAuthenticationValidator;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

/**
 * Answers a control-plane request whose handling threw, the same way on every frontend (HTTP/1.1, HTTP/2, HTTP/3
 * and the servlets).
 * <p>
 * A client error (see {@link #isClientError(Throwable)}) is answered {@code 400} with the exception's message, which
 * tells the caller what to correct. Anything else is a fault in MockServer: it is answered {@code 500} with a generic
 * message naming a correlation id, and logged once at {@code ERROR} with that id and the stack trace, so the
 * exception's text never reaches the caller as if it described their request.
 */
public final class ControlPlaneFailureResponse {

    public static final String UNEXPECTED_FAILURE_MESSAGE = "unexpected error processing request, see the MockServer log for correlation id: ";

    private ControlPlaneFailureResponse() {
    }

    /**
     * A failure the caller caused and can correct: input that is invalid (including JSON that could not be read or
     * failed schema validation, which every deserializer reports as an {@link IllegalArgumentException}), JSON a
     * handler could not read, or an operation this deployment does not support. A {@link JsonProcessingException}
     * from writing JSON ({@link StreamWriteException}) or from a type Jackson cannot handle
     * ({@link InvalidDefinitionException}) is a fault in MockServer, not in the input.
     */
    public static boolean isClientError(Throwable throwable) {
        return throwable instanceof IllegalArgumentException
            || throwable instanceof UnsupportedOperationException
            || throwable instanceof JsonProcessingException
            && !(throwable instanceof StreamWriteException)
            && !(throwable instanceof InvalidDefinitionException);
    }

    public static void write(MockServerLogger mockServerLogger, ResponseWriter responseWriter, HttpRequest request, Throwable throwable) {
        if (isClientError(throwable)) {
            HttpRequest logged = forLog(mockServerLogger, request);
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(correlationId(request))
                    .setHttpRequest(logged)
                    .setMessageFormat("exception processing request:{}error:{}")
                    .setArguments(logged, throwable.getMessage())
            );
            responseWriter.writeResponse(request, BAD_REQUEST, throwable.getMessage(), MediaType.create("text", "plain").toString());
        } else {
            responseWriter.writeResponse(request, INTERNAL_SERVER_ERROR, logUnexpectedFailure(mockServerLogger, request, throwable), MediaType.create("text", "plain").toString());
        }
    }

    /**
     * Answers a failure the caller has already decided is a fault in MockServer: {@code 500}, generic message, logged
     * once at {@code ERROR} with the stack trace, whatever the exception's type.
     */
    public static void writeUnexpectedFailure(MockServerLogger mockServerLogger, ResponseWriter responseWriter, HttpRequest request, Throwable throwable) {
        responseWriter.writeResponse(request, INTERNAL_SERVER_ERROR, logUnexpectedFailure(mockServerLogger, request, throwable), MediaType.create("text", "plain").toString());
    }

    /**
     * Logs a fault in MockServer once at {@code ERROR} with a correlation id and the stack trace.
     *
     * @return the generic message naming that correlation id, to answer the request with
     */
    public static String logUnexpectedFailure(MockServerLogger mockServerLogger, HttpRequest request, Throwable throwable) {
        String correlationId = correlationId(request);
        HttpRequest logged = forLog(mockServerLogger, request);
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.ERROR)
                .setCorrelationId(correlationId)
                .setHttpRequest(logged)
                .setMessageFormat("unexpected exception processing request:{}correlation id:{}")
                .setArguments(logged, correlationId)
                .setThrowable(throwable)
        );
        return UNEXPECTED_FAILURE_MESSAGE + correlationId;
    }

    // a failure before processAction leaves the proxy hop's credential on the request
    private static HttpRequest forLog(MockServerLogger mockServerLogger, HttpRequest request) {
        return ProxyAuthenticationValidator.withoutProxyCredential(request, mockServerLogger.getConfiguration());
    }

    /**
     * The {@code 500} a data-plane request whose processing threw is answered with. It is written as a mock response
     * ({@code apiResponse} false), so it carries CORS headers only when {@code enableCORSForAllResponses} is on.
     */
    public static HttpResponse dataPlaneFailureResponse(String message) {
        return HttpResponse.response()
            .withStatusCode(INTERNAL_SERVER_ERROR.code())
            .withReasonPhrase(INTERNAL_SERVER_ERROR.reasonPhrase())
            .withHeader("content-type", MediaType.create("text", "plain").toString() + "; charset=utf-8")
            .withBody(message);
    }

    private static String correlationId(HttpRequest request) {
        return request != null && request.getLogCorrelationId() != null ? request.getLogCorrelationId() : UUIDService.getNonSecureUUID();
    }
}

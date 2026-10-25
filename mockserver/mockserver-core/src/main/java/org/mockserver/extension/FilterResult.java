package org.mockserver.extension;

import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.Objects;

/**
 * What a {@link RequestFilter} decided for one request. <b>Experimental.</b>
 */
public final class FilterResult {

    private static final FilterResult PROCEED = new FilterResult(null, null);

    private final HttpRequest request;
    private final HttpResponse response;

    private FilterResult(HttpRequest request, HttpResponse response) {
        this.request = request;
        this.response = response;
    }

    /**
     * Continue with the request as it was before this filter ran; changes made to the copy the filter was given are
     * discarded.
     */
    public static FilterResult proceed() {
        return PROCEED;
    }

    /**
     * Continue with {@code request}, which may be the copy this filter was given, changed. Fields the transport owns
     * (such as the client address, TLS facts and stream id) are reset from the original request.
     */
    public static FilterResult proceedWith(HttpRequest request) {
        return new FilterResult(Objects.requireNonNull(request, "request"), null);
    }

    /**
     * Answer the request now with {@code response}; later filters, and matching, do not run.
     */
    public static FilterResult respond(HttpResponse response) {
        return new FilterResult(null, Objects.requireNonNull(response, "response"));
    }

    /**
     * @return true for {@link #proceed()} and {@link #proceedWith(HttpRequest)}
     */
    public boolean isProceed() {
        return response == null;
    }

    /**
     * @return true for {@link #respond(HttpResponse)}
     */
    public boolean isRespond() {
        return response != null;
    }

    /**
     * @return the request given to {@link #proceedWith(HttpRequest)}, or {@code null}
     */
    public HttpRequest request() {
        return request;
    }

    /**
     * @return the response given to {@link #respond(HttpResponse)}, or {@code null}
     */
    public HttpResponse response() {
        return response;
    }

    @Override
    public String toString() {
        return response != null ? "respond" : request != null ? "proceedWith" : "proceed";
    }
}

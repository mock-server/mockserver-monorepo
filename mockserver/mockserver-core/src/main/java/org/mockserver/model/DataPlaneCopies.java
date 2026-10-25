package org.mockserver.model;

/**
 * The copy and result rules of the data-plane request filter and response transformer, which run only when an
 * extension implementing one is loaded. A hook is given a deep copy, so nothing it does reaches the request or
 * response the server holds, and that input stays pristine for {@code proceed()} and fail-open. What a hook returns
 * is accepted as a fresh deep copy, so the server never holds an object the extension can still reach, and no hash
 * cached before an in-place edit survives into change detection or matching. A result is "changed" when it differs
 * from the input by value.
 * <p>
 * Internal: public only so the extension executor and the transports can use it; not part of the extension SPI.
 */
public final class DataPlaneCopies {

    private static final String CONTENT_LENGTH = "content-length";

    private DataPlaneCopies() {
    }

    /**
     * The copy given to a request filter: a deep copy of its input.
     */
    public static HttpRequest copyForFilter(HttpRequest input) {
        return input.deepCopy();
    }

    /**
     * The copy given to a response transformer: a deep copy of its input, without the streaming body.
     */
    public static HttpResponse copyForTransformer(HttpResponse input) {
        return input.deepCopy();
    }

    /**
     * Accepts the request a filter continued with, {@code proceedWith(result)}: a deep copy of it, with the fields
     * the transport owns re-stamped from {@code received}, and the record of the body as received kept only when
     * its body still equals {@code input}'s by value. {@code proceed()} needs none of this: it continues with
     * {@code input}.
     */
    public static HttpRequest acceptFilteredRequest(HttpRequest result, HttpRequest input, HttpRequest received) {
        return normalise(result).restampTransportFields(received).reconcileBodyProvenance(input);
    }

    /**
     * Accepts the response a transformer returned: a deep copy of it, carrying {@code input}'s streaming body, which
     * no transformer is shown. When the transformer changed the body but left the {@code Content-Length} it was given
     * (such as an upstream's on a forwarded response), that header is removed so the encoder sets the length of the
     * body actually sent, as {@link HttpResponse#update} does for a forward's response override; a
     * {@code Content-Length} the transformer changed itself is kept.
     *
     * @param input the response the transformer was given a copy of
     */
    public static HttpResponse acceptTransformedResponse(HttpResponse result, HttpResponse input) {
        HttpResponse accepted = normalise(result).withStreamingBody(input.getStreamingBody());
        String contentLength = accepted.getFirstHeader(CONTENT_LENGTH);
        if (!contentLength.isEmpty()
            && contentLength.equals(input.getFirstHeader(CONTENT_LENGTH))
            && !java.util.Objects.equals(accepted.getBody(), input.getBody())) {
            accepted.removeHeader(CONTENT_LENGTH);
        }
        return accepted;
    }

    /**
     * A deep copy, which has no cached hash or transient cache, for the request matching uses.
     */
    public static HttpRequest normalise(HttpRequest request) {
        return request.deepCopy();
    }

    /**
     * A deep copy, which has no cached hash, for the response written; it has no streaming body.
     */
    public static HttpResponse normalise(HttpResponse response) {
        return response.deepCopy();
    }

    public static boolean changed(HttpRequest accepted, HttpRequest input) {
        return !accepted.equals(input);
    }

    public static boolean changed(HttpResponse accepted, HttpResponse input) {
        return !accepted.equals(input);
    }
}

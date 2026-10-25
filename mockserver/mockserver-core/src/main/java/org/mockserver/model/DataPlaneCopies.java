package org.mockserver.model;

/**
 * The copy and result rules of the data-plane request filter and response transformer, which run only when an
 * extension implementing one is loaded. A hook is given a deep copy, so nothing it does reaches the request or
 * response the server holds, and that input stays pristine for {@code proceed()} and fail-open. What a hook returns
 * is accepted as a fresh deep copy, so the server never holds an object the extension can still reach, and no hash
 * cached before an in-place edit survives into change detection or matching. A result is "changed" when it differs
 * from the input by value.
 */
final class DataPlaneCopies {

    private DataPlaneCopies() {
    }

    /**
     * The copy given to a request filter: a deep copy of its input.
     */
    static HttpRequest copyForFilter(HttpRequest input) {
        return input.deepCopy();
    }

    /**
     * The copy given to a response transformer: a deep copy of its input, without the streaming body.
     */
    static HttpResponse copyForTransformer(HttpResponse input) {
        return input.deepCopy();
    }

    /**
     * Accepts the request a filter continued with, {@code proceedWith(result)}: a deep copy of it, with the fields
     * the transport owns re-stamped from {@code received}, and the record of the body as received kept only when
     * its body still equals {@code input}'s by value. {@code proceed()} needs none of this: it continues with
     * {@code input}.
     */
    static HttpRequest acceptFilteredRequest(HttpRequest result, HttpRequest input, HttpRequest received) {
        return normalise(result).restampTransportFields(received).reconcileBodyProvenance(input);
    }

    /**
     * Accepts the response a transformer returned: a deep copy of it, carrying {@code original}'s streaming body,
     * which no transformer is shown.
     */
    static HttpResponse acceptTransformedResponse(HttpResponse result, HttpResponse original) {
        return normalise(result).withStreamingBody(original.getStreamingBody());
    }

    /**
     * A deep copy, which has no cached hash or transient cache, for the request matching uses.
     */
    static HttpRequest normalise(HttpRequest request) {
        return request.deepCopy();
    }

    /**
     * A deep copy, which has no cached hash, for the response written; it has no streaming body.
     */
    static HttpResponse normalise(HttpResponse response) {
        return response.deepCopy();
    }

    static boolean changed(HttpRequest accepted, HttpRequest input) {
        return !accepted.equals(input);
    }

    static boolean changed(HttpResponse accepted, HttpResponse input) {
        return !accepted.equals(input);
    }
}

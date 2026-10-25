package org.mockserver.extension;

import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

/**
 * An {@link Extension} that sees every data-plane response just before it is written, and can replace it.
 * <b>Experimental:</b> this interface may change in the next minor release.
 * <p>
 * Transformers run in descending {@link #priority()}, then by descending {@link #name()}, so the transformer whose
 * filter ran first sees the response last. The chain runs on a {@code MockServer-ext-dataplane-*} thread with one
 * budget of {@code mockserver.extensionDataPlaneTimeoutMillis}, and takes one of
 * {@code mockserver.extensionDataPlaneMaxInFlight} slots, as for {@link RequestFilter}.
 * <p>
 * A transformer fails when it throws, returns {@code null}, does not return within the budget, or replaces the body
 * of a streamed response ({@link TransformContext#isStreamed()}). A failing transformer's response is replaced by a
 * {@code 500}, unless {@link #failOpen()} is true, when the response it was given is used instead.
 */
public interface ResponseTransformer extends Extension {

    /**
     * @param request  a copy of the request the response answers
     * @param response a copy of the response, which this transformer may change and return
     * @param context  read-only facts about the exchange
     * @return the response to write; {@code null} is a failure
     * @throws Exception which is a failure
     */
    HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) throws Exception;

    /**
     * @return true to write the response this transformer was given when it fails, or to skip it when no thread is
     * free; false (the default) to answer {@code 500}, or {@code 503} when no thread is free. Asked once, at start-up
     */
    default boolean failOpen() {
        return false;
    }
}

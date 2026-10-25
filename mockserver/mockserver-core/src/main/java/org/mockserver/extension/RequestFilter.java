package org.mockserver.extension;

import org.mockserver.model.HttpRequest;

/**
 * An {@link Extension} that sees every data-plane request before MockServer matches it, and can let it through,
 * change it, or answer it. <b>Experimental:</b> this interface may change in the next minor release.
 * <p>
 * Filters run in ascending {@link #priority()}, then by {@link #name()}; the first one that answers stops the chain.
 * The whole chain runs on a {@code MockServer-ext-dataplane-*} thread, never on a network thread, and has one budget
 * of {@code mockserver.extensionDataPlaneTimeoutMillis} from admission to its result. At most
 * {@code mockserver.extensionDataPlaneMaxInFlight} filter and transformer chains run at once; a request beyond that is
 * answered {@code 503}. A filter still running when the budget ends is interrupted, and whatever it returns
 * afterwards is discarded.
 * <p>
 * A filter fails when it throws, returns {@code null}, does not return within the budget, or breaks the contract (it
 * moves the request under {@code /mockserver/} or {@code /_mockserver_}, or changes a gRPC request's path or
 * {@code x-grpc-service} / {@code x-grpc-method}). A failing filter answers the request {@code 500}, unless
 * {@link #failOpen()} is true, when the request continues as it was before that filter ran.
 * <p>
 * A filter is not an authentication boundary: control-plane requests, and requests MockServer refuses earlier (such
 * as an authentication {@code 401}), never reach it.
 */
public interface RequestFilter extends Extension {

    /**
     * Decides what happens to one request.
     *
     * @param request a copy of the request, which this filter may change and return with
     *                {@link FilterResult#proceedWith(HttpRequest)}
     * @param context read-only facts about the request
     * @return {@link FilterResult#proceed()}, {@link FilterResult#proceedWith(HttpRequest)} or
     * {@link FilterResult#respond(org.mockserver.model.HttpResponse)}; {@code null} is a failure
     * @throws Exception which is a failure
     */
    FilterResult filter(HttpRequest request, FilterContext context) throws Exception;

    /**
     * @return true to let a request continue unchanged when this filter fails, or when no thread is free to run it;
     * false (the default) to answer it {@code 500}, or {@code 503} when no thread is free. Asked once, at start-up
     */
    default boolean failOpen() {
        return false;
    }
}

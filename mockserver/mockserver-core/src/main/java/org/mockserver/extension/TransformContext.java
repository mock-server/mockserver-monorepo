package org.mockserver.extension;

/**
 * Read-only facts about the exchange a {@link ResponseTransformer} is given: those about the request, as for a
 * {@link RequestFilter}, and three about the response. <b>Experimental.</b>
 */
public final class TransformContext extends FilterContext {

    private final boolean streamed;
    private final String expectationId;
    private final boolean generated;

    private TransformContext(FilterContext request, boolean streamed, String expectationId, boolean generated) {
        super(request);
        this.streamed = streamed;
        this.expectationId = expectationId;
        this.generated = generated;
    }

    /**
     * @param request       the facts about the request
     * @param streamed      whether the response body is streamed, so the transformer sees only its head
     * @param expectationId the id of the expectation that answered, or {@code null}
     * @param generated     whether MockServer made the response itself rather than an expectation or upstream
     */
    public static TransformContext of(FilterContext request, boolean streamed, String expectationId, boolean generated) {
        return new TransformContext(request, streamed, expectationId, generated);
    }

    /**
     * @return true when the body is streamed: the transformer sees no body and may change only the status and headers
     */
    public boolean isStreamed() {
        return streamed;
    }

    /**
     * @return the id of the expectation that answered, when known; {@code null} for a forwarded or generated response
     */
    public String expectationId() {
        return expectationId;
    }

    /**
     * @return true when MockServer made the response itself: today a no-match {@code 404}, an overload {@code 503}
     * (a full queue of delayed, templated or static-file responses) or a breakpoint abort; false for a response an
     * expectation, an upstream or a callback supplied
     */
    public boolean isGenerated() {
        return generated;
    }

    @Override
    public String toString() {
        return super.toString() + ", streamed=" + streamed + ", expectationId=" + expectationId + ", generated=" + generated;
    }
}

package org.mockserver.extension;

import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.List;

/**
 * The result of one request filter chain or response transformer chain, as the transport should act on it.
 * <ul>
 *     <li>Filter chain: {@link #isProceed()} continues to matching with {@link #request()}; {@link #isRespond()}
 *     writes a filter's {@link #response()} through the transformer chain; {@link #isFailure()} writes the failure
 *     {@link #response()} without any transformer.</li>
 *     <li>Transformer chain: {@link #response()} is what to write; with {@link #isFailure()} it is a failure response.</li>
 * </ul>
 */
public final class DataPlaneOutcome {

    public static final String THREW = "threw";
    public static final String TIMEOUT = "timeout";
    public static final String RETURNED_NULL = "null";
    public static final String CONTRACT_VIOLATION = "contract_violation";
    public static final String AT_CAPACITY = "at_capacity";
    public static final String STOPPING = "stopping";

    private final HttpRequest request;
    private final HttpResponse response;
    private final boolean respond;
    private final boolean failure;
    private final String extensionName;
    private final String failureType;
    private final List<String> changedBy;
    private final boolean skipped;

    private DataPlaneOutcome(HttpRequest request, HttpResponse response, boolean respond, boolean failure, String extensionName, String failureType, List<String> changedBy, boolean skipped) {
        this.request = request;
        this.response = response;
        this.respond = respond;
        this.failure = failure;
        this.extensionName = extensionName;
        this.failureType = failureType;
        this.changedBy = changedBy == null ? List.of() : List.copyOf(changedBy);
        this.skipped = skipped;
    }

    static DataPlaneOutcome proceed(HttpRequest request, List<String> changedBy, boolean skipped) {
        return new DataPlaneOutcome(request, null, false, false, null, null, changedBy, skipped);
    }

    static DataPlaneOutcome respond(HttpRequest request, HttpResponse response, String extensionName, List<String> changedBy) {
        return new DataPlaneOutcome(request, response, true, false, extensionName, null, changedBy, false);
    }

    static DataPlaneOutcome transformed(HttpRequest request, HttpResponse response, List<String> changedBy, boolean skipped) {
        return new DataPlaneOutcome(request, response, false, false, null, null, changedBy, skipped);
    }

    static DataPlaneOutcome failed(HttpRequest request, HttpResponse failureResponse, String extensionName, String failureType) {
        return new DataPlaneOutcome(request, failureResponse, false, true, extensionName, failureType, null, false);
    }

    /**
     * @return for a filter chain, the request to match with ({@link #isProceed()}), the input of the filter that
     * answered ({@link #isRespond()}), or the request as received ({@link #isFailure()}); for a transformer chain, the
     * request the response answers
     */
    public HttpRequest request() {
        return request;
    }

    /**
     * @return the response to write, or {@code null} when a filter chain proceeds
     */
    public HttpResponse response() {
        return response;
    }

    /**
     * @return true when a filter chain let the request continue to matching
     */
    public boolean isProceed() {
        return response == null;
    }

    /**
     * @return true when a request filter answered the request
     */
    public boolean isRespond() {
        return respond;
    }

    /**
     * @return true when {@link #response()} is a failure response, which must be written without any transformer
     */
    public boolean isFailure() {
        return failure;
    }

    /**
     * @return the extension that answered or failed, or {@code null}
     */
    public String extensionName() {
        return extensionName;
    }

    /**
     * @return why the chain failed ({@link #THREW}, {@link #TIMEOUT}, {@link #RETURNED_NULL},
     * {@link #CONTRACT_VIOLATION}, {@link #AT_CAPACITY} or {@link #STOPPING}), or {@code null}
     */
    public String failureType() {
        return failureType;
    }

    /**
     * @return the extensions whose result changed the request or response, in chain order
     */
    public List<String> changedBy() {
        return changedBy;
    }

    /**
     * @return true when the chain was skipped because no slot was free and every extension in it fails open
     */
    public boolean isSkipped() {
        return skipped;
    }

    @Override
    public String toString() {
        String kind = failure ? "failure(" + failureType + ", " + extensionName + ")" : respond ? "respond(" + extensionName + ")" : response != null ? "transformed" : "proceed";
        return kind + (response != null ? " status=" + response.getStatusCode() : "") + (changedBy.isEmpty() ? "" : " changedBy=" + changedBy) + (skipped ? " skipped" : "");
    }
}

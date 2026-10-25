package org.mockserver.model;

import java.util.Objects;

/**
 * Declarative retry/backoff recovery primitive attached to an {@link HttpResponse}:
 * "fail the first K matches, then serve the configured success response". This lets a
 * test deterministically exercise a client's retry/backoff logic against a dependency
 * that fails transiently and then recovers.
 * <p>
 * Counting is 1-based over the expectation's match count (attempt {@code n}):
 * <ul>
 *   <li>Attempts {@code 1..failTimes} serve {@code failResponse} (or, when none is
 *       configured, a default {@code 503 Service Unavailable}).</li>
 *   <li>Attempt {@code failTimes + 1} and beyond serve the configured success response.</li>
 * </ul>
 * So {@code failTimes = K} yields exactly K failures followed by success. When
 * {@code failTimes} is {@code null} or {@code <= 0} this primitive is inert and the
 * configured response is served unchanged (backward compatible).
 * <p>
 * Counting is INDEPENDENT of {@code Times}: a fail attempt still consumes a match of the
 * expectation but does not consume a {@code Times} use beyond what matching already does —
 * the expectation keeps matching across the failure window exactly as it would without
 * this primitive (see {@link org.mockserver.mock.action.http.HttpActionHandler}).
 * <p>
 * By default the failure counter is per-expectation (off the expectation's match count, so
 * no extra state is held). When {@code idempotencyHeader} is set, the counter is instead
 * keyed per {@code (expectationId, header-value)} in a node-local
 * {@link org.mockserver.mock.action.http.RecoveryAttemptRegistry}, so each distinct
 * idempotency key gets its own {@code 1..K} failure window while requests sharing a key
 * share one window. If {@code idempotencyHeader} is configured but the header is ABSENT on
 * a given request, that request falls back to the per-expectation match count.
 * <p>
 * Relationship to {@link HttpChaosProfile}: the chaos profile's
 * {@code succeedFirst}/{@code failRequestCount} count-window injects probabilistic faults
 * over a window; this primitive is a simpler, deterministic, response-level
 * "fail-then-succeed" with optional idempotency-key scoping, expressed directly on the
 * response action rather than as a chaos field.
 * <p>
 * Follows the model field/{@code withX}/getter convention so it round-trips without a
 * bespoke (de)serializer.
 */
public class RecoverAfter extends ObjectWithJsonToString {

    private int hashCode;
    private Integer failTimes;          // K; number of leading attempts that serve the failure response (null/<=0 = inert)
    private HttpResponse failResponse;  // optional failure response; null => default 503 Service Unavailable
    private String idempotencyHeader;   // optional request header whose value keys an independent failure window

    /**
     * Static builder: fail the first {@code failTimes} matches with the default
     * {@code 503 Service Unavailable}, then serve the configured success response.
     */
    public static RecoverAfter recoverAfter(int failTimes) {
        return new RecoverAfter().withFailTimes(failTimes);
    }

    public RecoverAfter withFailTimes(Integer failTimes) {
        this.failTimes = failTimes;
        this.hashCode = 0;
        return this;
    }

    RecoverAfter deepCopy() {
        return new RecoverAfter()
            .withFailTimes(failTimes)
            .withFailResponse(failResponse != null ? failResponse.deepCopy() : null)
            .withIdempotencyHeader(idempotencyHeader);
    }

    public Integer getFailTimes() {
        return failTimes;
    }

    public RecoverAfter withFailResponse(HttpResponse failResponse) {
        this.failResponse = failResponse;
        this.hashCode = 0;
        return this;
    }

    public HttpResponse getFailResponse() {
        return failResponse;
    }

    public RecoverAfter withIdempotencyHeader(String idempotencyHeader) {
        this.idempotencyHeader = idempotencyHeader;
        this.hashCode = 0;
        return this;
    }

    public String getIdempotencyHeader() {
        return idempotencyHeader;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        RecoverAfter that = (RecoverAfter) o;
        return Objects.equals(failTimes, that.failTimes) &&
            Objects.equals(failResponse, that.failResponse) &&
            Objects.equals(idempotencyHeader, that.idempotencyHeader);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(failTimes, failResponse, idempotencyHeader);
        }
        return hashCode;
    }
}

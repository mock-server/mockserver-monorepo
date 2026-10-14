package org.mockserver.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which expectation would serve a recorded request now, why each expectation evaluated ahead of it was passed over,
 * and how the request was handled when it arrived; returned by {@link MockServerClient#explain(String)}. The result
 * is recomputed when asked for, against the current expectations, scenario states and remaining Times.
 *
 * @param correlationId            the correlation ID of the explained request's log entries
 * @param timestamp                when the explanation was computed (ISO-8601)
 * @param note                     the server's reminder that the result is recomputed
 * @param request                  the recorded request that was explained
 * @param atArrival                how the request was handled when it arrived
 * @param sameOutcomeAsAtArrival   whether it would be handled the same way now, or {@code null} when that cannot be told
 * @param expectationsChangedSince whether the expectations changed at or after the moment the request arrived
 * @param expectationsLastChanged  when the expectations last changed (ISO-8601), or {@code null} if they never have
 * @param matchedExpectation       the expectation that would serve the request now, or {@code null} if none would
 * @param closestExpectationId     when none would, the expectation with the fewest differing fields, or {@code null}
 * @param skippedExpectations      the expectations evaluated ahead of the matched one, each with a reason
 * @param shadowedExpectations     lower-priority expectations that also match but are never reached
 * @param totalExpectations        how many expectations are stored
 * @param evaluatedExpectations    how many were evaluated
 * @param truncated                whether the evaluation budget ran out before every expectation was evaluated
 * @param maxExpectationsEvaluated the evaluation budget when {@code truncated}, otherwise {@code null}
 */
public record RequestExplanation(
    String correlationId,
    String timestamp,
    String note,
    ExplainedRequest request,
    Arrival atArrival,
    Boolean sameOutcomeAsAtArrival,
    boolean expectationsChangedSince,
    String expectationsLastChanged,
    ExplainedExpectation matchedExpectation,
    String closestExpectationId,
    List<ExplainedExpectation> skippedExpectations,
    List<ExplainedExpectation> shadowedExpectations,
    int totalExpectations,
    int evaluatedExpectations,
    boolean truncated,
    Integer maxExpectationsEvaluated
) {

    /**
     * Why an expectation was passed over, in the order the gates are applied when a request is served;
     * {@code UNKNOWN} is a reason this client does not know, sent by a newer server.
     */
    public enum Reason {
        NAMESPACE, INACTIVE, FIELDS_DIFFER, NOT_OPERATOR, CONVERSATION, SCENARIO_STATE, PERCENTAGE, LOWER_PRIORITY, UNKNOWN
    }

    /**
     * How the request was handled when it arrived.
     */
    public enum Outcome {
        MATCHED, NO_MATCH, FORWARDED_WITHOUT_EXPECTATION, RESPONDED_WITHOUT_EXPECTATION, UNKNOWN
    }

    /**
     * @param timestamp         when the request was recorded, as the event log formats it
     * @param method            the request method
     * @param path              the request path
     * @param bodyTruncated     whether the event log kept only the start of the body, so body matching used that copy
     * @param bodyTruncatedNote the server's note on the truncated body, or {@code null}
     */
    public record ExplainedRequest(String timestamp, String method, String path, boolean bodyTruncated, String bodyTruncatedNote) {
    }

    /**
     * @param outcome                how the request was handled
     * @param expectationId          the expectation that served it, or {@code null} unless {@code outcome} is {@code MATCHED}
     * @param expectationStillStored whether that expectation is still stored, or {@code null} unless {@code outcome} is {@code MATCHED}
     * @param forwardFailure         why the request's forward failed, or {@code null} unless it was forwarded and the forward
     *                               failed (and always from a server older than this field)
     */
    public record Arrival(Outcome outcome, String expectationId, Boolean expectationStillStored, ForwardFailure forwardFailure) {

        public Arrival(Outcome outcome, String expectationId, Boolean expectationStillStored) {
            this(outcome, expectationId, expectationStillStored, null);
        }
    }

    /**
     * The request was forwarded and the forward failed, so MockServer answered with a {@code 502}.
     *
     * @param statusCode           the status MockServer answered with, or {@code null}
     * @param reason               why the forward failed, redacted as the event log is and capped at 2048 characters
     * @param reasonTruncated      whether {@code reason} was capped
     * @param causeNamed           whether MockServer named the cause (when {@code false} the reason is the raw error), or {@code null}
     * @param forwardLoopSuspected whether the forward appears to have gone back to MockServer itself, or {@code null}
     * @param hint                 what to look at next, or {@code null}; sent when {@code causeNamed} is {@code false}
     */
    public record ForwardFailure(Integer statusCode, String reason, boolean reasonTruncated, Boolean causeNamed, Boolean forwardLoopSuspected, String hint) {
    }

    /**
     * One expectation as it was evaluated. The matched expectation has no {@code reason}; a skipped or shadowed one has
     * a reason and an explanation, and one compared field by field also has the differences and remediation hints.
     *
     * @param expectationId       the expectation's id
     * @param expectationMethod   the method it matches, or {@code null}
     * @param expectationPath     the path it matches, or {@code null}
     * @param position            its 1-based position in the order expectations are evaluated
     * @param priority            its priority
     * @param reason              why it was passed over, or {@code null} for the matched expectation (never for a skipped one)
     * @param explanation         why it was passed over, in plain language, or {@code null}
     * @param matches             whether the request matches its request matcher, or {@code null} when an earlier gate decided
     * @param differingFieldCount how many fields differ, or {@code null} when it was not compared field by field
     * @param differences         for each differing field, the differences found
     * @param remediation         for each differing field, a hint on how to change the expectation
     */
    public record ExplainedExpectation(
        String expectationId,
        String expectationMethod,
        String expectationPath,
        int position,
        int priority,
        Reason reason,
        String explanation,
        Boolean matches,
        Integer differingFieldCount,
        Map<String, List<String>> differences,
        Map<String, String> remediation
    ) {
    }

    static RequestExplanation fromJson(JsonNode root) {
        JsonNode request = root.path("request");
        JsonNode arrival = root.path("atArrival");
        return new RequestExplanation(
            text(root, "correlationId"),
            text(root, "timestamp"),
            text(root, "note"),
            new ExplainedRequest(text(request, "timestamp"), text(request, "method"), text(request, "path"),
                request.path("bodyTruncated").asBoolean(false), text(request, "bodyTruncatedNote")),
            new Arrival(enumOf(Outcome.class, text(arrival, "outcome"), Outcome.UNKNOWN), text(arrival, "expectationId"), bool(arrival, "expectationStillStored"),
                arrival.path("forwardFailure").isObject() ? forwardFailure(arrival.path("forwardFailure")) : null),
            bool(root, "sameOutcomeAsAtArrival"),
            root.path("expectationsChangedSince").asBoolean(false),
            text(root, "expectationsLastChanged"),
            root.has("matchedExpectation") ? expectation(root.path("matchedExpectation")) : null,
            text(root, "closestExpectationId"),
            expectations(root.path("skippedExpectations")),
            expectations(root.path("shadowedExpectations")),
            root.path("totalExpectations").asInt(),
            root.path("evaluatedExpectations").asInt(),
            root.path("truncated").asBoolean(false),
            root.hasNonNull("maxExpectationsEvaluated") ? root.path("maxExpectationsEvaluated").asInt() : null
        );
    }

    private static ForwardFailure forwardFailure(JsonNode node) {
        return new ForwardFailure(
            node.path("statusCode").isInt() ? node.path("statusCode").asInt() : null,
            text(node, "reason"),
            node.path("reasonTruncated").asBoolean(false),
            bool(node, "causeNamed"),
            bool(node, "forwardLoopSuspected"),
            text(node, "hint")
        );
    }

    private static List<ExplainedExpectation> expectations(JsonNode array) {
        List<ExplainedExpectation> expectations = new ArrayList<>();
        for (JsonNode node : array) {
            expectations.add(expectation(node));
        }
        return Collections.unmodifiableList(expectations);
    }

    private static ExplainedExpectation expectation(JsonNode node) {
        Map<String, List<String>> differences = new LinkedHashMap<>();
        node.path("differences").fields().forEachRemaining(field -> {
            List<String> values = new ArrayList<>();
            field.getValue().forEach(value -> values.add(value.asText()));
            differences.put(field.getKey(), Collections.unmodifiableList(values));
        });
        Map<String, String> remediation = new LinkedHashMap<>();
        node.path("remediation").fields().forEachRemaining(field -> remediation.put(field.getKey(), field.getValue().asText()));
        String reason = text(node, "reason");
        return new ExplainedExpectation(
            text(node, "expectationId"),
            text(node, "expectationMethod"),
            text(node, "expectationPath"),
            node.path("position").asInt(),
            node.path("priority").asInt(),
            reason == null ? null : enumOf(Reason.class, reason, Reason.UNKNOWN),
            text(node, "explanation"),
            bool(node, "matches"),
            node.hasNonNull("differingFieldCount") ? node.path("differingFieldCount").asInt() : null,
            Collections.unmodifiableMap(differences),
            Collections.unmodifiableMap(remediation)
        );
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}

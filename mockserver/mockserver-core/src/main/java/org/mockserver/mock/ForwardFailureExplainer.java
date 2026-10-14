package org.mockserver.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.UpstreamConnectFailure;
import org.mockserver.log.model.LogEntry;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.List;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Reads back the failed forwards the event log holds: for each, the request and the reason its {@code 502} gave,
 * which the forward action names when it can (TLS, HTTP/2, connection set-up, an undecodable response, a connection
 * that could not be made or was lost, a forward back to MockServer itself). The reason is the logged one, redacted as
 * the event log is ({@code redactSecretsInLog}) and capped; nothing is re-classified here.
 */
public final class ForwardFailureExplainer {

    static final int MAX_REASON_CHARS = 2048;
    private static final int REASON_ARGUMENT = 1;

    private ForwardFailureExplainer() {
    }

    /**
     * @param failures the {@code failed to forward request} entries, most recent first
     * @return {@code forwardFailureCount} and one {@code forwardFailures} node per entry
     */
    public static ObjectNode explain(ObjectMapper objectMapper, List<LogEntry> failures, Configuration configuration) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode failureNodes = objectMapper.createArrayNode();
        for (LogEntry failure : failures) {
            failureNodes.add(describe(objectMapper, failure, configuration));
        }
        result.put("forwardFailureCount", failureNodes.size());
        result.set("forwardFailures", failureNodes);
        return result;
    }

    private static ObjectNode describe(ObjectMapper objectMapper, LogEntry failure, Configuration configuration) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("correlationId", failure.getCorrelationId());
        node.put("timestamp", failure.getTimestamp());
        if (failure.getHttpRequest() instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) failure.getHttpRequest();
            node.put("method", request.getMethod() != null ? request.getMethod().getValue() : "");
            node.put("path", request.getPath() != null ? request.getPath().getValue() : "");
        }
        return describeFailure(node, failure, configuration);
    }

    /**
     * Adds what the {@code 502} said about one failed forward to {@code node}: its status code, the reason and whether
     * MockServer named the cause.
     */
    static ObjectNode describeFailure(ObjectNode node, LogEntry failure, Configuration configuration) {
        HttpResponse response = failure.getHttpResponse();
        if (response != null && response.getStatusCode() != null) {
            node.put("statusCode", response.getStatusCode());
        }
        Object[] arguments = failure.getArguments(configuration);
        String reason = arguments != null && arguments.length > REASON_ARGUMENT && arguments[REASON_ARGUMENT] != null ? String.valueOf(arguments[REASON_ARGUMENT]) : "unknown error";
        node.put("forwardLoopSuspected", reason.contains(UpstreamConnectFailure.FORWARD_LOOP));
        if (reason.length() > MAX_REASON_CHARS) {
            node.put("reason", reason.substring(0, MAX_REASON_CHARS));
            node.put("reasonTruncated", true);
        } else {
            node.put("reason", reason);
        }
        // the forward action puts the reason in the 502's body only when it named the cause
        boolean causeNamed = response != null && isNotBlank(response.getBodyAsString());
        node.put("causeNamed", causeNamed);
        if (!causeNamed) {
            node.put("hint", "MockServer could not name this failure's cause, so the reason is the raw error; retrieve_logs with this correlationId shows the request's whole lifecycle, including any exception logged for it");
        }
        return node;
    }
}

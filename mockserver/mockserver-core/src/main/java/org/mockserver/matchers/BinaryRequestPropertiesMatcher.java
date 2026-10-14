package org.mockserver.matchers;

import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.BinaryRequestDefinition;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.slf4j.event.Level;

import java.util.Collections;
import java.util.List;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;

public class BinaryRequestPropertiesMatcher extends AbstractHttpRequestMatcher {

    private BinaryRequestDefinition binaryRequestDefinition;
    private BinaryMatcher binaryMatcher;

    public BinaryRequestPropertiesMatcher(Configuration configuration, MockServerLogger mockServerLogger) {
        super(configuration, mockServerLogger);
    }

    @Override
    public List<HttpRequest> getHttpRequests() {
        return Collections.emptyList();
    }

    @Override
    public boolean apply(RequestDefinition requestDefinition) {
        BinaryRequestDefinition binaryRequest = requestDefinition instanceof BinaryRequestDefinition ? (BinaryRequestDefinition) requestDefinition : null;
        if (this.binaryRequestDefinition == null || !this.binaryRequestDefinition.equals(binaryRequest)) {
            this.hashCode = 0;
            this.binaryRequestDefinition = binaryRequest;
            if (binaryRequest != null) {
                this.binaryMatcher = new BinaryMatcher(mockServerLogger, binaryRequest.getBinaryData());
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean matches(final MatchDifference context, final RequestDefinition requestDefinition) {
        if (requestDefinition instanceof BinaryRequestDefinition) {
            BinaryRequestDefinition request = (BinaryRequestDefinition) requestDefinition;
            boolean overallMatch = matchesBinary(context, request);
            if (!controlPlaneMatcher && (context == null || !context.isSuppressMatchResultLogging())) {
                if (overallMatch) {
                    if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(EXPECTATION_MATCHED)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(requestDefinition.getLogCorrelationId())
                                .setHttpRequest(requestDefinition)
                                .setExpectation(this.expectation)
                                .setMessageFormat(this.expectation == null ? REQUEST_DID_MATCH : EXPECTATION_DID_MATCH)
                                .setArguments(request, (this.expectation == null ? this : this.expectation.clone()))
                        );
                    }
                } else if (context != null && !context.mayWriteNotMatchedEntry()) {
                    if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        context.notMatchedEntryWithheld();
                    }
                } else {
                    if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        if (context != null) {
                            context.notMatchedEntryWritten();
                        }
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(EXPECTATION_NOT_MATCHED)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(requestDefinition.getLogCorrelationId())
                                .setHttpRequest(requestDefinition)
                                .setExpectation(this.expectation)
                                .setMessageFormat(this.expectation == null ? didNotMatchRequestBecause : didNotMatchExpectationWithoutBecause)
                                .setArguments(request, (this.expectation == null ? this : this.expectation.clone()))
                        );
                    }
                }
            }
            return overallMatch;
        } else {
            return requestDefinition == null;
        }
    }

    private boolean matchesBinary(MatchDifference context, BinaryRequestDefinition request) {
        if (isActive()) {
            if (request == this.binaryRequestDefinition) {
                return true;
            } else if (this.binaryRequestDefinition == null) {
                return true;
            } else {
                if (context != null) {
                    context.currentField(MatchDifference.Field.BINARY_BODY);
                }
                boolean bodyMatch = binaryMatcher == null || binaryMatcher.matches(context, request.getBinaryData());
                if (!bodyMatch && context != null) {
                    context.addDifference(mockServerLogger, "binary body didn't match");
                }
                return applyNotOperators(bodyMatch, request.isNot(), this.binaryRequestDefinition.isNot(), not);
            }
        } else {
            return false;
        }
    }

    private int hashCode;

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = java.util.Objects.hash(expectation);
        }
        return hashCode;
    }
}

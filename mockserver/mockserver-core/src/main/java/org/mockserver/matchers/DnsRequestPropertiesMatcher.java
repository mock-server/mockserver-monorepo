package org.mockserver.matchers;

import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.DnsRequestDefinition;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.slf4j.event.Level;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;

public class DnsRequestPropertiesMatcher extends AbstractHttpRequestMatcher {

    private DnsRequestDefinition dnsRequestDefinition;

    public DnsRequestPropertiesMatcher(Configuration configuration, MockServerLogger mockServerLogger) {
        super(configuration, mockServerLogger);
    }

    @Override
    public List<HttpRequest> getHttpRequests() {
        return Collections.emptyList();
    }

    @Override
    public boolean apply(RequestDefinition requestDefinition) {
        DnsRequestDefinition dnsRequest = requestDefinition instanceof DnsRequestDefinition ? (DnsRequestDefinition) requestDefinition : null;
        if (this.dnsRequestDefinition == null || !this.dnsRequestDefinition.equals(dnsRequest)) {
            this.hashCode = 0;
            this.dnsRequestDefinition = dnsRequest;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean matches(final MatchDifference context, final RequestDefinition requestDefinition) {
        if (requestDefinition instanceof DnsRequestDefinition) {
            DnsRequestDefinition request = (DnsRequestDefinition) requestDefinition;
            boolean overallMatch = matchesDns(context, request);
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

    private boolean matchesDns(MatchDifference context, DnsRequestDefinition request) {
        if (isActive()) {
            if (request == this.dnsRequestDefinition) {
                return true;
            } else if (this.dnsRequestDefinition == null) {
                return true;
            } else {
                boolean nameMatch = true;
                boolean typeMatch = true;
                boolean classMatch = true;

                if (this.dnsRequestDefinition.getDnsName() != null) {
                    if (context != null) {
                        context.currentField(MatchDifference.Field.DNS_NAME);
                    }
                    nameMatch = stripTrailingDot(this.dnsRequestDefinition.getDnsName())
                        .equalsIgnoreCase(stripTrailingDot(request.getDnsName()));
                    if (!nameMatch && context != null) {
                        context.addDifference(mockServerLogger, "dns name didn't match");
                    }
                }

                if (nameMatch && this.dnsRequestDefinition.getDnsType() != null) {
                    if (context != null) {
                        context.currentField(MatchDifference.Field.DNS_TYPE);
                    }
                    typeMatch = this.dnsRequestDefinition.getDnsType() == request.getDnsType();
                    if (!typeMatch && context != null) {
                        context.addDifference(mockServerLogger, "dns type didn't match");
                    }
                }

                if (nameMatch && typeMatch && this.dnsRequestDefinition.getDnsClass() != null) {
                    if (context != null) {
                        context.currentField(MatchDifference.Field.DNS_CLASS);
                    }
                    classMatch = this.dnsRequestDefinition.getDnsClass() == request.getDnsClass();
                    if (!classMatch && context != null) {
                        context.addDifference(mockServerLogger, "dns class didn't match");
                    }
                }

                boolean matched = nameMatch && typeMatch && classMatch;
                return applyNotOperators(matched, request.isNot(), this.dnsRequestDefinition.isNot(), not);
            }
        } else {
            return false;
        }
    }

    private static String stripTrailingDot(String name) {
        if (name != null && name.endsWith(".")) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }

    private int hashCode;

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(expectation);
        }
        return hashCode;
    }
}


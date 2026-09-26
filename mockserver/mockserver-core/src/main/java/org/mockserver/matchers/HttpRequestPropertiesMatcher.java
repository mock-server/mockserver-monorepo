package org.mockserver.matchers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Joiner;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.codec.ExpandedParameterDecoder;
import org.mockserver.codec.JsonSchemaBodyDecoder;
import org.mockserver.codec.PathParametersDecoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.deserializers.body.StrictBodyDTODeserializer;
import org.mockserver.serialization.model.BodyDTO;
import org.slf4j.event.Level;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;
import static org.mockserver.matchers.MatchDifference.Field.*;
import static org.mockserver.model.NottableString.string;

/**
 * Matches HTTP request properties against expectation criteria.
 * <p>
 * <strong>Null/Blank Matcher Behavior:</strong> When a specific matcher (e.g., method, path, headers)
 * is null or blank, it matches <em>all</em> values for that property. This allows expectations to
 * match broadly without specifying every field. For example:
 * </p>
 * <ul>
 *   <li>No method matcher → matches any HTTP method (GET, POST, etc.)</li>
 *   <li>No path matcher → matches any request path</li>
 *   <li>No header matcher → matches requests with any headers</li>
 * </ul>
 * <p>
 * This "filter" behavior improves UX: unspecified fields act as wildcards, so users can create
 * simple expectations without boilerplate "match anything" patterns.
 * </p>
 *
 * <p>
 * <strong>Concurrency:</strong> a single matcher instance is shared between the control plane
 * (which rebuilds the compiled criterion in {@link #apply(RequestDefinition)} when an expectation
 * is created or updated — a single-writer path) and the data plane (which reads the compiled
 * criterion in {@link #matches} from many Netty event-loop threads concurrently). To stop a
 * concurrent control-plane update from exposing a half-rebuilt matcher to an in-flight match, all
 * per-criterion compiled state lives in an immutable {@link Compiled} holder built fully inside
 * {@code apply()} and published through a single {@code volatile} reference ({@link #compiled}).
 * {@code matches()} snapshots that reference once on entry and reads everything from the snapshot,
 * so it sees either the entire old criterion or the entire new one — never a torn mix.
 * </p>
 *
 * @author jamesdbloom
 */
@SuppressWarnings("rawtypes")
public class HttpRequestPropertiesMatcher extends AbstractHttpRequestMatcher {

    private static final String[] excludedFields = {"mockServerLogger", "compiled", "objectMapperWithStrictBodyDTODeserializer", "matcherBuilder", "expandedParameterDecoder", "controlPlaneMatcher", "responseInProgress", "objectMapper"};
    private static final String COMMA = ",";
    private static final String REQUEST_NOT_OPERATOR_IS_ENABLED = COMMA + NEW_LINE + "request 'not' operator is enabled";
    private static final String EXPECTATION_REQUEST_NOT_OPERATOR_IS_ENABLED = COMMA + NEW_LINE + "expectation's request 'not' operator is enabled";
    private static final String EXPECTATION_REQUEST_MATCHER_NOT_OPERATOR_IS_ENABLED = COMMA + NEW_LINE + "expectation's request matcher 'not' operator is enabled";
    private static final PathParametersDecoder pathParametersParser = new PathParametersDecoder();
    private static final ObjectWriter TO_STRING_OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(true, false);
    private final ExpandedParameterDecoder expandedParameterDecoder;
    private int hashCode;
    /**
     * The fully-built, immutable compiled criterion. Reassigned (never mutated) by {@code apply()}
     * on the single-writer control-plane path and read by {@code matches()} on data-plane threads;
     * {@code volatile} provides the happens-before edge so a reader sees a wholly-built holder.
     */
    private volatile Compiled compiled = Compiled.EMPTY;
    private ObjectMapper objectMapperWithStrictBodyDTODeserializer;
    private MatcherBuilder matcherBuilder;

    /**
     * Immutable snapshot of every per-criterion compiled field. Built in full by
     * {@link HttpRequestPropertiesMatcher#apply(RequestDefinition)} and published atomically via the
     * single {@code volatile} {@link HttpRequestPropertiesMatcher#compiled} reference, so a
     * concurrent {@code matches()} either sees this whole object or the previous one — never a
     * field-by-field torn mix.
     */
    private static final class Compiled {

        /**
         * The pre-{@code apply()} state: matches the original instance-field defaults where every
         * compiled field (including {@code httpRequests}) was {@code null} until the first
         * {@code apply()} ran.
         */
        private static final Compiled EMPTY = new Compiled();

        private final HttpRequest httpRequest;
        private final List<HttpRequest> httpRequests;
        private final RegexStringMatcher methodMatcher;
        private final RegexStringMatcher pathMatcher;
        private final MultiValueMapMatcher pathParameterMatcher;
        private final MultiValueMapMatcher queryStringParameterMatcher;
        private final BodyMatcher bodyMatcher;
        private final MultiValueMapMatcher headerMatcher;
        private final HashMapMatcher cookieMatcher;
        private final BooleanMatcher keepAliveMatcher;
        private final BooleanMatcher sslMatcher;
        private final ExactStringMatcher protocolMatcher;
        private final ClientCertificateMatcher clientCertificateMatcher;
        private final JwtMatcher jwtMatcher;
        private final JsonSchemaBodyDecoder jsonSchemaBodyParser;

        /**
         * Pre-apply EMPTY state only — every field {@code null} (httpRequests included), matching
         * the original uninitialised instance-field defaults.
         */
        private Compiled() {
            this.httpRequest = null;
            this.httpRequests = null;
            this.methodMatcher = null;
            this.pathMatcher = null;
            this.pathParameterMatcher = null;
            this.queryStringParameterMatcher = null;
            this.bodyMatcher = null;
            this.headerMatcher = null;
            this.cookieMatcher = null;
            this.keepAliveMatcher = null;
            this.sslMatcher = null;
            this.protocolMatcher = null;
            this.clientCertificateMatcher = null;
            this.jwtMatcher = null;
            this.jsonSchemaBodyParser = null;
        }

        /**
         * Holder for a null (or non-HttpRequest) criterion: every field matcher is null, mirroring
         * the original behaviour where {@code apply(null)} left all the {@code with*} fields unset
         * but set {@code httpRequests = singletonList(null)}.
         */
        private Compiled(HttpRequest httpRequest) {
            this(httpRequest, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        private Compiled(
            HttpRequest httpRequest,
            RegexStringMatcher methodMatcher,
            RegexStringMatcher pathMatcher,
            MultiValueMapMatcher pathParameterMatcher,
            MultiValueMapMatcher queryStringParameterMatcher,
            BodyMatcher bodyMatcher,
            MultiValueMapMatcher headerMatcher,
            HashMapMatcher cookieMatcher,
            BooleanMatcher keepAliveMatcher,
            BooleanMatcher sslMatcher,
            ExactStringMatcher protocolMatcher,
            ClientCertificateMatcher clientCertificateMatcher,
            JwtMatcher jwtMatcher,
            JsonSchemaBodyDecoder jsonSchemaBodyParser
        ) {
            this.httpRequest = httpRequest;
            this.httpRequests = Collections.singletonList(httpRequest);
            this.methodMatcher = methodMatcher;
            this.pathMatcher = pathMatcher;
            this.pathParameterMatcher = pathParameterMatcher;
            this.queryStringParameterMatcher = queryStringParameterMatcher;
            this.bodyMatcher = bodyMatcher;
            this.headerMatcher = headerMatcher;
            this.cookieMatcher = cookieMatcher;
            this.keepAliveMatcher = keepAliveMatcher;
            this.sslMatcher = sslMatcher;
            this.protocolMatcher = protocolMatcher;
            this.clientCertificateMatcher = clientCertificateMatcher;
            this.jwtMatcher = jwtMatcher;
            this.jsonSchemaBodyParser = jsonSchemaBodyParser;
        }
    }

    public HttpRequestPropertiesMatcher(Configuration configuration, MockServerLogger mockServerLogger) {
        super(configuration, mockServerLogger);
        this.expandedParameterDecoder = new ExpandedParameterDecoder(configuration, mockServerLogger);
    }

    public HttpRequest getHttpRequest() {
        return compiled.httpRequest;
    }

    public boolean hasBodyMatcher() {
        return compiled.bodyMatcher != null;
    }

    @Override
    public List<HttpRequest> getHttpRequests() {
        return compiled.httpRequests;
    }

    @Override
    public boolean apply(RequestDefinition requestDefinition) {
        HttpRequest httpRequest = requestDefinition instanceof HttpRequest httpReq ? httpReq : null;
        Compiled current = this.compiled;
        if (current.httpRequest == null || !current.httpRequest.equals(httpRequest)) {
            this.hashCode = 0;
            Compiled rebuilt;
            if (httpRequest != null) {
                // Build every per-criterion field matcher into a fully-populated immutable holder
                // first, then publish it through a single volatile assignment, so a concurrent
                // matches() never observes a new httpRequest paired with a stale or null field
                // matcher — it sees either the whole previous holder or the whole new one.
                // Exact-case (case-sensitive) matching of method, path and string body is opt-in via
                // matchExactCase and only applies to data-plane request matching. Control-plane
                // matchers (clear/verify/retrieve filters) keep the historical case-insensitive
                // behaviour so administrative operations are unaffected. Header/cookie/query map
                // matchers below always use the default case-insensitive constructors.
                boolean caseSensitive = !controlPlaneMatcher && configuration.matchExactCase();
                BodyMatcher bodyMatcher = buildBodyMatcher(httpRequest.getBody());
                // The JsonSchemaBodyDecoder is only dereferenced (in BodyMatching.bodyMatches) when a
                // JSON-type body matcher converts an XML/form actual body to JSON. On the data-plane
                // path the body matcher is fixed at build time, so for any non-JSON (or absent) body
                // matcher the decoder is dead weight and we skip allocating it. The control-plane path
                // is different: it can build a *fresh* JSON body matcher at match time from the
                // candidate request's parsed BodyDTO (see matchesBody) and feed it back through the
                // same decoder, so a control-plane matcher must always carry the decoder regardless of
                // its own body matcher type.
                JsonSchemaBodyDecoder jsonSchemaBodyParser =
                    (controlPlaneMatcher
                        || bodyMatcher instanceof JsonStringMatcher
                        || bodyMatcher instanceof JsonSchemaMatcher
                        || bodyMatcher instanceof JsonPathMatcher
                        || (bodyMatcher instanceof AllOfBodyMatcher && ((AllOfBodyMatcher) bodyMatcher).containsJsonMatcher()))
                        ? new JsonSchemaBodyDecoder(configuration, mockServerLogger, expectation, httpRequest)
                        : null;
                rebuilt = new Compiled(
                    httpRequest,
                    new RegexStringMatcher(mockServerLogger, httpRequest.getMethod(), controlPlaneMatcher, caseSensitive),
                    new RegexStringMatcher(mockServerLogger, pathParametersParser.normalisePathWithParametersForMatching(httpRequest), controlPlaneMatcher, caseSensitive),
                    new MultiValueMapMatcher(mockServerLogger, httpRequest.getPathParameters(), controlPlaneMatcher),
                    new MultiValueMapMatcher(mockServerLogger, httpRequest.getQueryStringParameters(), controlPlaneMatcher),
                    bodyMatcher,
                    new MultiValueMapMatcher(mockServerLogger, httpRequest.getHeaders(), controlPlaneMatcher),
                    new HashMapMatcher(mockServerLogger, httpRequest.getCookies(), controlPlaneMatcher),
                    new BooleanMatcher(mockServerLogger, httpRequest.isKeepAlive()),
                    new BooleanMatcher(mockServerLogger, httpRequest.isSecure()),
                    new ExactStringMatcher(mockServerLogger, httpRequest.getProtocol() != null ? string(httpRequest.getProtocol().name()) : null),
                    new ClientCertificateMatcher(mockServerLogger, httpRequest.getClientCertificate(), controlPlaneMatcher),
                    new JwtMatcher(mockServerLogger, httpRequest.getJwt(), controlPlaneMatcher),
                    jsonSchemaBodyParser
                );
            } else {
                rebuilt = new Compiled(null);
            }
            // single volatile publish — the happens-before edge that gives data-plane readers a
            // wholly-built (never torn) view of the new criterion
            this.compiled = rebuilt;
            return true;
        } else {
            return false;
        }
    }

    public HttpRequestPropertiesMatcher withControlPlaneMatcher(boolean controlPlaneMatcher) {
        this.controlPlaneMatcher = controlPlaneMatcher;
        return this;
    }

    private BodyMatcher buildBodyMatcher(Body body) {
        return BodyMatcherBuilder.buildBodyMatcher(configuration, mockServerLogger, body, controlPlaneMatcher);
    }

    public boolean matches(final MatchDifference context, final RequestDefinition requestDefinition) {
        if (requestDefinition instanceof HttpRequest request) {
            // snapshot the compiled criterion once so the whole match runs against a single,
            // wholly-built version even if the control plane rebuilds it concurrently
            final Compiled compiled = this.compiled;
            // The "because" message is only consumed by the INFO log below, so only
            // allocate the builder when it will be used (a no-op otherwise — see
            // the matching gate in failFast). Null when not logging at INFO.
            boolean logBecause = !controlPlaneMatcher && (context == null || !context.isSuppressMatchResultLogging()) && mockServerLogger.isEnabledForInstance(Level.INFO);
            StringBuilder becauseBuilder = logBecause ? new StringBuilder() : null;
            boolean overallMatch = matches(compiled, context, request, becauseBuilder);
            if (!controlPlaneMatcher && (context == null || !context.isSuppressMatchResultLogging())) {
                if (overallMatch) {
                    if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(EXPECTATION_MATCHED)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(requestDefinition.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setExpectation(this.expectation)
                                .setMessageFormat(this.expectation == null ? REQUEST_DID_MATCH : EXPECTATION_DID_MATCH)
                                .setArguments(request, (this.expectation == null ? this : this.expectation.clone()))
                        );
                    }
                } else if (becauseBuilder != null) {
                    becauseBuilder.replace(0, 1, "");
                    String because = becauseBuilder.toString();
                    if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        String messageFormat;
                        if (this.expectation == null) {
                            messageFormat = didNotMatchRequestBecause;
                        } else {
                            String expectationId = this.expectation.getId();
                            String expectationLabel = isNotBlank(expectationId) ? " expectation \"" + expectationId + "\"" : EXPECTATION;
                            if (becauseBuilder.length() > 0) {
                                messageFormat = REQUEST_DID_NOT_MATCH + matcherDescription + expectationLabel + BECAUSE;
                            } else {
                                messageFormat = REQUEST_DID_NOT_MATCH + matcherDescription + expectationLabel;
                            }
                        }
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(EXPECTATION_NOT_MATCHED)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(requestDefinition.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setExpectation(this.expectation)
                                .setMessageFormat(messageFormat)
                                .setArguments(request, (this.expectation == null ? this : this.expectation.clone()), because)
                                .setBecause(because)
                        );
                    }
                }
            }
            return overallMatch;
        } else if (requestDefinition instanceof OpenAPIDefinition) {
            if (matcherBuilder == null) {
                matcherBuilder = new MatcherBuilder(configuration, mockServerLogger);
            }
            boolean overallMatch = false;
            for (HttpRequest request : matcherBuilder.transformsToMatcher(requestDefinition).getHttpRequests()) {
                if (matches(request.cloneWithLogCorrelationId())) {
                    overallMatch = true;
                    break;
                }
            }
            return overallMatch;
        } else {
            return requestDefinition == null;
        }
    }

    private boolean matches(Compiled compiled, MatchDifference context, HttpRequest request, StringBuilder becauseBuilder) {
        if (isActive()) {
            final HttpRequest httpRequest = compiled.httpRequest;
            if (request == httpRequest) {
                return true;
            } else if (httpRequest == null) {
                return true;
            } else {
                MatchDifferenceCount matchDifferenceCount = new MatchDifferenceCount(request);
                if (request != null) {
                    boolean methodMatches = StringUtils.isBlank(request.getMethod().getValue()) || matches(METHOD, context, compiled.methodMatcher, request.getMethod());
                    if (failFast(compiled, compiled.methodMatcher, context, matchDifferenceCount, becauseBuilder, methodMatches, METHOD)) {
                        return false;
                    }

                    boolean pathMatches = StringUtils.isBlank(request.getPath().getValue()) || matches(PATH, context, compiled.pathMatcher, controlPlaneMatcher ? pathParametersParser.normalisePathWithParametersForMatching(request) : request.getPath());
                    // extractPathParameters clones (or allocates) the request's path Parameters and
                    // runs the path-template regex even when this expectation declares no {pathParam}
                    // templates. When the matcher has no path parameters the extraction is pure
                    // overhead: it never adds an entry, the path-parameter matcher is blank (so it
                    // matches anything), and the post-match withPathParameters assignment below
                    // normalises empty/null identically whether handed the request's own Parameters
                    // or a content-equal clone of them. So only run extraction when the expectation
                    // actually declares path parameters; otherwise reuse the request's own Parameters.
                    boolean expectationHasPathParameters = httpRequest.getPathParameters() != null && !httpRequest.getPathParameters().isEmpty();
                    Parameters pathParameters = expectationHasPathParameters ? null : request.getPathParameters();
                    if (expectationHasPathParameters) {
                        try {
                            pathParameters = pathParametersParser.extractPathParameters(httpRequest, request);
                        } catch (IllegalArgumentException iae) {
                            if (!httpRequest.getPath().isBlank()) {
                                if (context != null) {
                                    context.currentField(PATH);
                                    context.addDifference(mockServerLogger, iae.getMessage());
                                }
                                pathMatches = false;
                            }
                        }
                    }
                    if (failFast(compiled, compiled.pathMatcher, context, matchDifferenceCount, becauseBuilder, pathMatches, PATH)) {
                        return false;
                    }

                    boolean bodyMatches = bodyMatches(compiled, context, request);
                    if (failFast(compiled, compiled.bodyMatcher, context, matchDifferenceCount, becauseBuilder, bodyMatches, BODY)) {
                        return false;
                    }

                    boolean headersMatch = matches(HEADERS, context, compiled.headerMatcher, request.getHeaders());
                    if (failFast(compiled, compiled.headerMatcher, context, matchDifferenceCount, becauseBuilder, headersMatch, HEADERS)) {
                        return false;
                    }

                    boolean cookiesMatch = matches(COOKIES, context, compiled.cookieMatcher, request.getCookies());
                    if (failFast(compiled, compiled.cookieMatcher, context, matchDifferenceCount, becauseBuilder, cookiesMatch, COOKIES)) {
                        return false;
                    }

                    boolean pathParametersMatches = true;
                    if (!httpRequest.getPath().isBlank()) {
                        if (!controlPlaneMatcher) {
                            expandedParameterDecoder.splitParameters(httpRequest.getPathParameters(), pathParameters);
                        }
                        MultiValueMapMatcher pathParameterMatcher = compiled.pathParameterMatcher;
                        if (controlPlaneMatcher) {
                            Parameters controlPlaneParameters;
                            try {
                                controlPlaneParameters = pathParametersParser.extractPathParameters(request, httpRequest);
                            } catch (IllegalArgumentException iae) {
                                controlPlaneParameters = new Parameters();
                            }
                            pathParameterMatcher = new MultiValueMapMatcher(mockServerLogger, controlPlaneParameters, controlPlaneMatcher);

                        }
                        pathParametersMatches = matches(PATH_PARAMETERS, context, pathParameterMatcher, pathParameters);
                    }
                    if (failFast(compiled, compiled.pathParameterMatcher, context, matchDifferenceCount, becauseBuilder, pathParametersMatches, PATH_PARAMETERS)) {
                        return false;
                    }

                    if (!controlPlaneMatcher) {
                        expandedParameterDecoder.splitParameters(httpRequest.getQueryStringParameters(), request.getQueryStringParameters());
                    }
                    boolean queryStringParametersMatches = matches(QUERY_PARAMETERS, context, compiled.queryStringParameterMatcher, request.getQueryStringParameters());
                    if (failFast(compiled, compiled.queryStringParameterMatcher, context, matchDifferenceCount, becauseBuilder, queryStringParametersMatches, QUERY_PARAMETERS)) {
                        return false;
                    }

                    boolean keepAliveMatches = matches(KEEP_ALIVE, context, compiled.keepAliveMatcher, request.isKeepAlive());
                    if (failFast(compiled, compiled.keepAliveMatcher, context, matchDifferenceCount, becauseBuilder, keepAliveMatches, KEEP_ALIVE)) {
                        return false;
                    }

                    boolean sslMatches = matches(SECURE, context, compiled.sslMatcher, request.isSecure());
                    if (failFast(compiled, compiled.sslMatcher, context, matchDifferenceCount, becauseBuilder, sslMatches, SECURE)) {
                        return false;
                    }

                    boolean protocolMatches = matches(PROTOCOL, context, compiled.protocolMatcher, request.getProtocol() != null ? request.getProtocol().getNottableName() : null);
                    if (failFast(compiled, compiled.protocolMatcher, context, matchDifferenceCount, becauseBuilder, protocolMatches, PROTOCOL)) {
                        return false;
                    }

                    boolean clientCertificateMatches = matches(CLIENT_CERTIFICATE, context, compiled.clientCertificateMatcher, request.getClientCertificateChain());
                    if (failFast(compiled, compiled.clientCertificateMatcher, context, matchDifferenceCount, becauseBuilder, clientCertificateMatches, CLIENT_CERTIFICATE)) {
                        return false;
                    }

                    boolean jwtMatches = matches(JWT, context, compiled.jwtMatcher, compiled.jwtMatcher == null ? null : request.getFirstHeader(compiled.jwtMatcher.getHeaderName()));
                    if (failFast(compiled, compiled.jwtMatcher, context, matchDifferenceCount, becauseBuilder, jwtMatches, JWT)) {
                        return false;
                    }

                    boolean finalMatchResult = applyNotOperators(
                        matchDifferenceCount.getFailures() == 0,
                        request.isNot(),
                        httpRequest.isNot(),
                        not
                    );
                    if (!controlPlaneMatcher && finalMatchResult) {
                        // ensure actions have path parameters available to them
                        request.withPathParameters(pathParameters);
                        // expose the matched expectation's path-pattern regex capture groups (numbered and
                        // named) on the request so a response/forward template can reference them; best-effort
                        // and never affects the already-decided match result above
                        extractPathGroups(httpRequest, request);
                    }
                    return finalMatchResult;
                } else {
                    return applyNotOperators(true, false, httpRequest.isNot(), not);
                }
            }
        }
        return false;
    }

    /**
     * Extract the numbered/named regex capture groups from the matched expectation's path pattern against
     * the request path and stash them on the request for response/forward templates to read. Only invoked
     * on the data plane after a successful match.
     * <p>
     * The fields are always cleared first so the served request only ever carries groups from the matcher
     * that ultimately wins. The same live request object is matched against every expectation in a single
     * scan, and a candidate can fully match here yet still be skipped by a later gate (percentage, scenario
     * state, conversation matcher); without the reset that skipped candidate's groups would leak onto a
     * request that is then served by a different expectation. Clearing first also means a winning literal
     * or no-capture-group path correctly exposes no groups rather than a previous candidate's.
     * <p>
     * Extraction itself is skipped (after the reset) when the expectation path has no '(' so cannot contain
     * a capturing group, keeping the common literal/path-parameter case free of regex work. The
     * caseSensitive flag mirrors the path matcher (matchExactCase, data plane only) so extraction compiles
     * with the same flags the match used. Always best-effort: PathGroupExtractor never throws.
     */
    private void extractPathGroups(HttpRequest matcherRequest, HttpRequest request) {
        // reset first so a skipped earlier candidate's groups never leak onto the served request
        request.withPathGroups(null);
        request.withNamedPathGroups(null);
        if (matcherRequest.getPath() == null || request.getPath() == null) {
            return;
        }
        String patternValue = matcherRequest.getPath().getValue();
        if (patternValue == null || patternValue.indexOf('(') < 0) {
            return;
        }
        boolean caseSensitive = configuration.matchExactCase();
        PathGroupExtractor.PathGroups pathGroups = PathGroupExtractor.extract(patternValue, request.getPath().getValue(), caseSensitive);
        if (pathGroups.hasGroups()) {
            request.withPathGroups(pathGroups.getNumberedGroups());
            if (!pathGroups.getNamedGroups().isEmpty()) {
                request.withNamedPathGroups(pathGroups.getNamedGroups());
            }
        }
    }

    private boolean failFast(Compiled compiled, Matcher<?> matcher, MatchDifference context, MatchDifferenceCount matchDifferenceCount, StringBuilder becauseBuilder, boolean fieldMatches, MatchDifference.Field fieldName) {
        // The human-readable "because" message is only ever consumed when INFO
        // logging is on, so matches(...) allocates becauseBuilder only then (and
        // never for a control-plane matcher); a null builder is the single source
        // of truth for "do not build the because string". Building it — the
        // per-field appends and, more expensively, generateHintsForField — is
        // otherwise wasted work allocated per field, per matcher, per request, so
        // a performance-tuned deployment (log level below INFO) does not pay for
        // strings it discards. The match-difference counter and the fail-fast
        // result below stay unconditional, and the MatchDifference itself is still
        // populated by the field matchers, so detailedMatchFailures /
        // debugMismatch / explainUnmatched / verification are unaffected.
        // update because builder
        if (becauseBuilder != null) {
            becauseBuilder
                .append(NEW_LINE)
                .append(fieldName.getName()).append(fieldMatches ? MATCHED : DID_NOT_MATCH);
            if (context != null && context.getDifferences(fieldName) != null && !context.getDifferences(fieldName).isEmpty()) {
                becauseBuilder
                    .append(COLON_NEW_LINES)
                    .append(Joiner.on(NEW_LINE).join(context.getDifferences(fieldName)));
            }
            if (!fieldMatches) {
                List<String> hints = generateHintsForField(compiled, fieldName, matchDifferenceCount.getHttpRequest());
                for (String hint : hints) {
                    becauseBuilder.append(NEW_LINE).append(hint);
                }
            }
        }
        if (!fieldMatches) {
            if (becauseBuilder != null) {
                if (matchDifferenceCount.getHttpRequest().isNot()) {
                    becauseBuilder
                        .append(REQUEST_NOT_OPERATOR_IS_ENABLED);
                }
                if (compiled.httpRequest.isNot()) {
                    becauseBuilder
                        .append(EXPECTATION_REQUEST_NOT_OPERATOR_IS_ENABLED);
                }
                if (not) {
                    becauseBuilder
                        .append(EXPECTATION_REQUEST_MATCHER_NOT_OPERATOR_IS_ENABLED);
                }
            }
        }
        // update match difference and potentially fail fast
        if (!fieldMatches) {
            matchDifferenceCount.incrementFailures();
        }
        // Fail-fast (early non-match return) is only SOUND when there are no NOT
        // operators anywhere. The short-circuit asks "have we failed so far?"
        // (failures != 0) and feeds that through applyNotOperators — but a partial
        // "failures != 0" is NOT the final base-match (which is "failures == 0 once
        // every field has been evaluated"). With an odd number of NOT flags and zero
        // failures accumulated so far, failures != 0 is false, which applyNotOperators
        // negates to true, prematurely returning a (wrong) non-match — so every field
        // that matches before the first mismatching field would falsely short-circuit
        // a NOT expectation. The only sound base-match under NOT is the final
        // applyNotOperators(failures == 0, ...) computed once all fields are evaluated.
        // So short-circuit ONLY when no NOT flag is in play (the common case — the vast
        // majority of expectations have no NOT operators, so the fail-fast performance
        // optimisation is preserved for them). When any NOT flag is set, never
        // short-circuit: let matches() evaluate every field (the per-field failure
        // bookkeeping above still runs for all of them) and rely on the single correct
        // final applyNotOperators(failures == 0, ...).
        // A diagnostic evaluation may also opt out, per request, so it can see every differing
        // field rather than only the first (MatchDifference.collectAllDifferences). No production
        // match path sets the flag — only debugMismatch and explainUnmatched do — so matching
        // BEHAVIOUR for real requests is unchanged. The cost is one null check plus at most one
        // boolean load on a context already dereferenced above: a single well-predicted branch,
        // not zero. This path is benchmark-guarded (MatchingBenchmark), so state it accurately.
        boolean anyNotOperator = matchDifferenceCount.getHttpRequest().isNot() || compiled.httpRequest.isNot() || not;
        if (!anyNotOperator && (context == null || !context.isCollectAllDifferences())
            && matcher != null && !matcher.isBlank() && configuration.matchersFailFast()) {
            return matchDifferenceCount.getFailures() != 0;
        }
        return false;
    }

    private List<String> generateHintsForField(Compiled compiled, MatchDifference.Field fieldName, HttpRequest request) {
        HttpRequest httpRequest = compiled.httpRequest;
        if (httpRequest == null || request == null) {
            return Collections.emptyList();
        }
        switch (fieldName) {
            case PATH:
                return MatchFailureHints.generateHints(fieldName, httpRequest.getPath(), request.getPath());
            case HEADERS:
                return MatchFailureHints.generateHints(fieldName, httpRequest.getHeaders(), request.getHeaders());
            case BODY:
                return MatchFailureHints.generateHints(fieldName, httpRequest.getBody(), request.getBody());
            default:
                return Collections.emptyList();
        }
    }

    private boolean bodyMatches(Compiled compiled, MatchDifference context, HttpRequest request) {
        boolean bodyMatches;
        BodyMatcher bodyMatcher = compiled.bodyMatcher;
        HttpRequest httpRequest = compiled.httpRequest;
        if (bodyMatcher != null) {
            if (controlPlaneMatcher) {
                // Control-plane fast path: a clear/verify/retrieve body filter whose stringified
                // form exactly equals the candidate request's stringified body is treated as a
                // match. Both sides must have a real body before stringifying — otherwise
                // String.valueOf(null) collapses to the literal "null", which would (a) make two
                // absent bodies "match" and, worse, (b) let a literal body filter of the text
                // "null" match a request that has NO body at all. Requiring both bodies non-null
                // keeps a no-body request out of this fast path; it can still match through the
                // bodyMatcher branch below if the filter genuinely matches an absent body.
                if (httpRequest.getBody() != null && request.getBody() != null && String.valueOf(httpRequest.getBody()).equalsIgnoreCase(String.valueOf(request.getBody()))) {
                    bodyMatches = true;
                } else if (bodyMatches(compiled, bodyMatcher, context, request)) {
                    // allow match of entries in EchoServer log (i.e. for java client integration tests)
                    bodyMatches = true;
                } else {
                    if (isNotBlank(request.getBodyAsJsonOrXmlString())) {
                        try {
                            BodyDTO bodyDTO = getObjectMapperWithStrictBodyDTODeserializer().readValue(request.getBodyAsJsonOrXmlString(), BodyDTO.class);
                            if (bodyDTO != null) {
                                bodyMatches = bodyMatches(
                                    compiled,
                                    buildBodyMatcher(bodyDTO.buildObject()),
                                    context,
                                    httpRequest
                                );
                            } else {
                                bodyMatches = false;
                            }
                        } catch (Throwable ignore) {
                            // ignore this exception as this exception would typically get thrown for "normal" HTTP requests (i.e. not clear or retrieve)
                            bodyMatches = false;
                        }
                    } else {
                        bodyMatches = false;
                    }
                }
            } else {
                bodyMatches = bodyMatches(compiled, bodyMatcher, context, request);
            }
        } else {
            bodyMatches = true;
        }
        return bodyMatches;
    }

    @SuppressWarnings("unchecked")
    private boolean bodyMatches(Compiled compiled, BodyMatcher bodyMatcher, MatchDifference context, HttpRequest request) {
        // Delegate to the shared body-dispatch so request and response body matching stay in lock
        // step. The optional-body short-circuit there models "no actual body" as
        // getBodyAsString() == null, which is exactly request.getBody() == null for a request (an
        // empty-but-present body returns "" not null), so behaviour is identical to the previous
        // inline implementation — including the multipart, binary original/compressed and
        // JSON/XML/GraphQL/JsonRpc dispatch and the MatchDifference context population.
        return BodyMatching.bodyMatches(
            bodyMatcher,
            compiled.httpRequest.getBody().getOptional(),
            BodyMatching.of(request),
            context,
            compiled.jsonSchemaBodyParser,
            mockServerLogger
        );
    }

    private <T> boolean matches(MatchDifference.Field field, MatchDifference context, Matcher<T> matcher, T t) {
        if (context != null) {
            context.currentField(field);
        }
        boolean result = false;

        if (matcher == null) {
            result = true;
        } else if (matcher.matches(context, t)) {
            result = true;
        }

        return result;
    }

    @Override
    public String toString() {
        try {
            return TO_STRING_OBJECT_WRITER
                .writeValueAsString(compiled.httpRequest);
        } catch (Exception e) {
            return super.toString();
        }
    }

    @Override
    @JsonIgnore
    public String[] fieldsExcludedFromEqualsAndHashCode() {
        return excludedFields;
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
        if (!super.equals(o)) {
            return false;
        }
        HttpRequestPropertiesMatcher that = (HttpRequestPropertiesMatcher) o;
        return Objects.equals(compiled.httpRequest, that.compiled.httpRequest);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), compiled.httpRequest);
        }
        return hashCode;
    }

    private ObjectMapper getObjectMapperWithStrictBodyDTODeserializer() {
        if (objectMapperWithStrictBodyDTODeserializer == null) {
            objectMapperWithStrictBodyDTODeserializer = ObjectMapperFactory.createObjectMapper(new StrictBodyDTODeserializer());
        }
        return objectMapperWithStrictBodyDTODeserializer;
    }
}

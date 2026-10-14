package org.mockserver.matchers;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Encoding;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.mockserver.openapi.OpenAPIParser;
import org.mockserver.openapi.OpenAPISerialiser;
import org.mockserver.openapi.examples.JsonNodeExampleSerializer;
import org.mockserver.serialization.ObjectMapperFactory;
import org.slf4j.event.Level;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.JsonSchemaBody.jsonSchema;
import static org.mockserver.model.NottableOptionalString.optional;
import static org.mockserver.model.NottableSchemaString.schemaString;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.ParameterStyle.*;
import static org.mockserver.openapi.OpenAPIParser.OPEN_API_LOAD_ERROR;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;
import static org.slf4j.event.Level.ERROR;
import static org.slf4j.event.Level.TRACE;

public class HttpRequestsPropertiesMatcher extends AbstractHttpRequestMatcher {

    private static final ObjectWriter TO_STRING_OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(true, false);
    private int hashCode;
    private OpenAPIDefinition openAPIDefinition;
    private List<HttpRequestPropertiesMatcher> httpRequestPropertiesMatchers;
    private List<HttpRequest> httpRequests;
    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(new JsonNodeExampleSerializer()).writerWithDefaultPrettyPrinter();

    protected HttpRequestsPropertiesMatcher(Configuration configuration, MockServerLogger mockServerLogger) {
        super(configuration, mockServerLogger);
    }

    public List<HttpRequestPropertiesMatcher> getHttpRequestPropertiesMatchers() {
        return httpRequestPropertiesMatchers;
    }

    @Override
    public List<HttpRequest> getHttpRequests() {
        return httpRequests;
    }

    @Override
    public boolean apply(RequestDefinition requestDefinition) {
        return apply(requestDefinition, new ArrayList<>());
    }

    @VisibleForTesting
    public boolean apply(RequestDefinition requestDefinition, List<LogEntry> logEntries) {
        OpenAPIDefinition openAPIDefinition = requestDefinition instanceof OpenAPIDefinition ? (OpenAPIDefinition) requestDefinition : null;
        if (this.openAPIDefinition == null || !this.openAPIDefinition.equals(openAPIDefinition)) {
            this.openAPIDefinition = openAPIDefinition;
            if (openAPIDefinition != null && isNotBlank(openAPIDefinition.getSpecUrlOrPayload())) {
                httpRequestPropertiesMatchers = new ArrayList<>();
                httpRequests = new ArrayList<>();
                OpenAPISerialiser openAPISerialiser = new OpenAPISerialiser(mockServerLogger, configuration);
                try {
                    OpenAPI openAPI = buildOpenAPI(openAPIDefinition.getSpecUrlOrPayload(), mockServerLogger, configuration);
                    try {
                        String contextPathPrefix = isNotBlank(openAPIDefinition.getContextPathPrefix()) ? openAPIDefinition.getContextPathPrefix() : configuration.openAPIContextPathPrefix();
                        final Map<String, List<Pair<String, Operation>>> stringListMap = openAPISerialiser.retrieveOperations(openAPI, openAPIDefinition.getOperationId(), contextPathPrefix);
                        stringListMap
                            .forEach((path, operations) -> operations
                                .forEach(methodOperationPair -> {
                                    Operation operation = methodOperationPair.getValue();
                                    if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                                        operation.getRequestBody().getContent().forEach(handleRequestBody(openAPIDefinition, openAPI, path, methodOperationPair, Boolean.TRUE.equals(operation.getRequestBody().getRequired()), logEntries));
                                    } else {
                                        HttpRequest httpRequest = createHttpRequest(openAPIDefinition, openAPI, path, methodOperationPair);
                                        try {
                                            addRequestMatcher(openAPIDefinition, methodOperationPair, httpRequest, "");
                                        } catch (Throwable throwable) {
                                            logEntries.add(
                                                new LogEntry()
                                                    .setLogLevel(ERROR)
                                                    .setMessageFormat("exception while creating adding request matcher for operation:{}method:{}in open api:{}")
                                                    .setArguments(methodOperationPair.getRight().getOperationId(), methodOperationPair.getLeft(), openAPIDefinition)
                                            );
                                        }
                                    }
                                }));
                    } catch (Throwable throwable) {
                        logEntries.add(
                            new LogEntry()
                                .setLogLevel(ERROR)
                                .setMessageFormat((StringUtils.isBlank(throwable.getMessage()) || !throwable.getMessage().contains(OPEN_API_LOAD_ERROR) ? OPEN_API_LOAD_ERROR + (isNotBlank(throwable.getMessage()) ? ", " : "") : "") + throwable.getMessage() + " for open api:{}")
                                .setArguments(openAPIDefinition)
                                .setThrowable(throwable)
                        );
                    }
                } catch (Throwable throwable) {
                    String message = (StringUtils.isBlank(throwable.getMessage()) || !throwable.getMessage().contains(OPEN_API_LOAD_ERROR) ? OPEN_API_LOAD_ERROR + (isNotBlank(throwable.getMessage()) ? ", " : "") : "") + throwable.getMessage();
                    throw new IllegalArgumentException(message, throwable);
                }
                logEntries.forEach(mockServerLogger::logEvent);
            }
            this.hashCode = 0;
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setHttpRequest(requestDefinition)
                        .setMessageFormat("created request matcher{}for open api definition{}")
                        .setArguments(this, requestDefinition)
                );
            }
            return true;
        } else {
            return false;
        }
    }

    private HttpRequest createHttpRequest(OpenAPIDefinition openAPIDefinition, OpenAPI openAPI, String path, Pair<String, Operation> methodOperationPair) {
        HttpRequest httpRequest = new HttpRequest()
            .withMethod(methodOperationPair.getKey())
            .withPath(path);
        Operation operation = methodOperationPair.getValue();
        // parameters
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                Schema<?> schema = Optional
                    .ofNullable(parameter.getSchema())
                    .orElse(Optional
                        .ofNullable(parameter.getContent())
                        .flatMap(content -> content
                            .values()
                            .stream()
                            .map(MediaType::getSchema)
                            .findFirst()
                        )
                        .orElse(null)
                    );
                if (schema != null) {
                    try {
                        NottableString name = (parameter.getRequired() != null && parameter.getRequired() ? string(parameter.getName()) : optional(parameter.getName())).withStyle(parameterStyle(parameter.getExplode(), parameter.getStyle()));
                        if (schema.getType() != null) {
                            name = name.withSchemaType(schema.getType());
                        }
                        if (parameter.getAllowEmptyValue() != null && parameter.getAllowEmptyValue()) {
                            schema.nullable(true);
                        }
                        if (Boolean.TRUE.equals(parameter.getAllowReserved())) {
                            throw new IllegalArgumentException("allowReserved field is not supported on parameters, found on operation: \"" + methodOperationPair.getRight().getOperationId() + "\" method: \"" + methodOperationPair.getLeft() + "\" parameter: \"" + name + "\" in: \"" + parameter.getIn() + "\"");
                        }
                        switch (parameter.getIn()) {
                            case "path": {
                                httpRequest.withPathParameter(name, schemaString(OBJECT_WRITER.writeValueAsString(schema)));
                                break;
                            }
                            case "query": {
                                httpRequest.withQueryStringParameter(name, schemaString(OBJECT_WRITER.writeValueAsString(schema)));
                                break;
                            }
                            case "header": {
                                httpRequest.withHeader(name, schemaString(OBJECT_WRITER.writeValueAsString(schema)));
                                break;
                            }
                            case "cookie": {
                                httpRequest.withCookie(name, schemaString(OBJECT_WRITER.writeValueAsString(schema)));
                                break;
                            }
                            default:
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setLogLevel(ERROR)
                                        .setMessageFormat("unknown value for the parameter in property, expected \"query\", \"header\", \"path\" or \"cookie\" found \"" + parameter.getIn() + "\"")
                                );
                        }
                    } catch (IOException exception) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(ERROR)
                                .setMessageFormat("exception while creating adding parameter{}from the schema{}")
                                .setArguments(parameter, openAPIDefinition)
                                .setThrowable(exception)
                        );
                    }
                }
            }
            // set matching key matching style to ensure all values are matched against any schema (not just one)
            if (httpRequest.getPathParameters() != null) {
                httpRequest.getPathParameters().withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY);
            }
            if (httpRequest.getQueryStringParameters() != null) {
                httpRequest.getQueryStringParameters().withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY);
            }
            if (httpRequest.getHeaders() != null) {
                httpRequest.getHeaders().withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY);
            }
        }
        // security schemes — per OpenAPI spec, operation-level security overrides global security
        Map<String, Set<String>> headerRequirements = new HashMap<>();
        Map<String, Set<String>> queryStringParameterRequirements = new HashMap<>();
        Map<String, Set<String>> cookieRequirements = new HashMap<>();
        List<SecurityRequirement> operationSecurity = methodOperationPair.getRight().getSecurity();
        if (operationSecurity != null) {
            buildSecurityValues(openAPI, headerRequirements, queryStringParameterRequirements, cookieRequirements, operationSecurity);
        } else {
            buildSecurityValues(openAPI, headerRequirements, queryStringParameterRequirements, cookieRequirements, openAPI.getSecurity());
        }
        if (!headerRequirements.isEmpty()) {
            if (headerRequirements.keySet().size() > 1) {
                for (Map.Entry<String, Set<String>> headerMatchEntry : headerRequirements.entrySet()) {
                    httpRequest.withHeader("?" + headerMatchEntry.getKey(), Joiner.on("|").join(headerMatchEntry.getValue()));
                }
                httpRequest.withHeader(Joiner.on("|").join(headerRequirements.keySet()), ".*");
            } else if (!queryStringParameterRequirements.isEmpty() || !cookieRequirements.isEmpty()) {
                httpRequest.withHeader("?" + Joiner.on("|").join(headerRequirements.keySet()), Joiner.on("|").join(headerRequirements.values().stream().flatMap(Collection::stream).collect(Collectors.toList())));
            } else {
                httpRequest.withHeader(Joiner.on("|").join(headerRequirements.keySet()), Joiner.on("|").join(headerRequirements.values().stream().flatMap(Collection::stream).collect(Collectors.toList())));
            }
        }
        if (!queryStringParameterRequirements.isEmpty()) {
            if (queryStringParameterRequirements.keySet().size() > 1) {
                for (Map.Entry<String, Set<String>> queryStringParameterMatchEntry : queryStringParameterRequirements.entrySet()) {
                    httpRequest.withQueryStringParameter("?" + queryStringParameterMatchEntry.getKey(), Joiner.on("|").join(queryStringParameterMatchEntry.getValue()));
                }
                httpRequest.withQueryStringParameter(Joiner.on("|").join(queryStringParameterRequirements.keySet()), ".*");
            } else if (!headerRequirements.isEmpty() || !cookieRequirements.isEmpty()) {
                httpRequest.withQueryStringParameter("?" + Joiner.on("|").join(queryStringParameterRequirements.keySet()), Joiner.on("|").join(queryStringParameterRequirements.values().stream().flatMap(Collection::stream).collect(Collectors.toList())));
            } else {
                httpRequest.withQueryStringParameter(Joiner.on("|").join(queryStringParameterRequirements.keySet()), Joiner.on("|").join(queryStringParameterRequirements.values().stream().flatMap(Collection::stream).collect(Collectors.toList())));
            }
        }
        if (!cookieRequirements.isEmpty()) {
            if (cookieRequirements.keySet().size() > 1) {
                for (Map.Entry<String, Set<String>> cookieMatchEntry : cookieRequirements.entrySet()) {
                    httpRequest.withCookie("?" + cookieMatchEntry.getKey(), Joiner.on("|").join(cookieMatchEntry.getValue()));
                }
                httpRequest.withCookie(Joiner.on("|").join(cookieRequirements.keySet()), ".*");
            } else if (!queryStringParameterRequirements.isEmpty() || !headerRequirements.isEmpty()) {
                httpRequest.withCookie("?" + Joiner.on("|").join(cookieRequirements.keySet()), Joiner.on("|").join(cookieRequirements.values().stream().flatMap(Collection::stream).collect(Collectors.toList())));
            } else {
                httpRequest.withCookie(Joiner.on("|").join(cookieRequirements.keySet()), Joiner.on("|").join(cookieRequirements.values().stream().flatMap(Collection::stream).collect(Collectors.toList())));
            }
        }
        return httpRequest;
    }

    private ParameterStyle parameterStyle(Boolean explode, Parameter.StyleEnum style) {
        ParameterStyle result = null;
        if (explode != null && style != null) {
            switch (style) {
                case MATRIX:
                    if (explode) {
                        result = MATRIX_EXPLODED;
                    } else {
                        result = MATRIX;
                    }
                    break;
                case LABEL:
                    if (explode) {
                        result = LABEL_EXPLODED;
                    } else {
                        result = LABEL;
                    }
                    break;
                case FORM:
                    if (explode) {
                        result = FORM_EXPLODED;
                    } else {
                        result = FORM;
                    }
                    break;
                case SIMPLE:
                    if (explode) {
                        result = SIMPLE_EXPLODED;
                    } else {
                        result = SIMPLE;
                    }
                    break;
                case SPACEDELIMITED:
                    if (explode) {
                        result = SPACE_DELIMITED_EXPLODED;
                    } else {
                        result = SPACE_DELIMITED;
                    }
                    break;
                case PIPEDELIMITED:
                    if (explode) {
                        result = PIPE_DELIMITED_EXPLODED;
                    } else {
                        result = PIPE_DELIMITED;
                    }
                    break;
                case DEEPOBJECT:
                    result = DEEP_OBJECT;
                    break;
            }
        }
        return result;
    }

    private ParameterStyle parameterStyle(Boolean explode, Encoding.StyleEnum style) {
        ParameterStyle result = null;
        if (explode != null && style != null) {
            switch (style) {
                case FORM:
                    if (explode) {
                        result = FORM_EXPLODED;
                    } else {
                        result = FORM;
                    }
                    break;
                case SPACE_DELIMITED:
                    if (explode) {
                        result = SPACE_DELIMITED_EXPLODED;
                    } else {
                        result = SPACE_DELIMITED;
                    }
                    break;
                case PIPE_DELIMITED:
                    if (explode) {
                        result = PIPE_DELIMITED_EXPLODED;
                    } else {
                        result = PIPE_DELIMITED;
                    }
                    break;
                case DEEP_OBJECT:
                    result = DEEP_OBJECT;
                    break;
            }
        }
        return result;
    }

    private void buildSecurityValues(OpenAPI openAPI, Map<String, Set<String>> headerRequirements, Map<String, Set<String>> queryStringParameterRequirements, Map<String, Set<String>> cookieRequirements, List<SecurityRequirement> security) {
        if (security != null) {
            for (SecurityRequirement securityRequirement : security) {
                if (securityRequirement != null) {
                    for (String securityRequirementName : securityRequirement.keySet()) {
                        if (openAPI.getComponents() != null && openAPI.getComponents().getSecuritySchemes() != null && openAPI.getComponents().getSecuritySchemes().get(securityRequirementName) != null) {
                            SecurityScheme securityScheme = openAPI.getComponents().getSecuritySchemes().get(securityRequirementName);
                            String scheme = securityScheme.getScheme();
                            switch (securityScheme.getType()) {
                                case APIKEY: {
                                    String parameterName = securityScheme.getName();
                                    if (isNotBlank(parameterName)) {
                                        switch (securityScheme.getIn() != null ? securityScheme.getIn() : SecurityScheme.In.HEADER) {
                                            case COOKIE: {
                                                Set<String> cookieValues = cookieRequirements.get(parameterName);
                                                String cookieValue = ".+";
                                                if (cookieValues != null) {
                                                    cookieValues.add(cookieValue);
                                                } else {
                                                    cookieRequirements.put(parameterName, new HashSet<>(Collections.singletonList(cookieValue)));
                                                }
                                                break;
                                            }
                                            case QUERY: {
                                                Set<String> queryStringParameterValues = queryStringParameterRequirements.get(parameterName);
                                                String queryStringParameterValue = ".+";
                                                if (queryStringParameterValues != null) {
                                                    queryStringParameterValues.add(queryStringParameterValue);
                                                } else {
                                                    queryStringParameterRequirements.put(parameterName, new HashSet<>(Collections.singletonList(queryStringParameterValue)));
                                                }
                                                break;
                                            }
                                            default:
                                            case HEADER: {
                                                Set<String> headerValues = headerRequirements.get(parameterName);
                                                String headerValue = ".+";
                                                if (headerValues != null) {
                                                    headerValues.add(headerValue);
                                                } else {
                                                    headerRequirements.put(parameterName, new HashSet<>(Collections.singletonList(headerValue)));
                                                }
                                                break;
                                            }
                                        }
                                    }
                                    break;
                                }
                                case HTTP:
                                case OAUTH2:
                                case OPENIDCONNECT: {
                                    String parameterName = AUTHORIZATION.toString();
                                    switch (securityScheme.getIn() != null ? securityScheme.getIn() : SecurityScheme.In.HEADER) {
                                        case COOKIE: {
                                            Set<String> cookieValues = cookieRequirements.get(parameterName);
                                            String cookieValue = (isNotBlank(scheme) ? scheme : "") + ".+";
                                            if (cookieValues != null) {
                                                cookieValues.add(cookieValue);
                                            } else {
                                                cookieRequirements.put(parameterName, new HashSet<>(Collections.singletonList(cookieValue)));
                                            }
                                            break;
                                        }
                                        case QUERY: {
                                            Set<String> queryStringParameterValues = queryStringParameterRequirements.get(parameterName);
                                            String queryStringParameterValue = (isNotBlank(scheme) ? scheme : "") + ".+";
                                            if (queryStringParameterValues != null) {
                                                queryStringParameterValues.add(queryStringParameterValue);
                                            } else {
                                                queryStringParameterRequirements.put(parameterName, new HashSet<>(Collections.singletonList(queryStringParameterValue)));
                                            }
                                            break;
                                        }
                                        default:
                                        case HEADER: {
                                            Set<String> headerValues = headerRequirements.get(parameterName);
                                            String headerValue = (isNotBlank(scheme) ? scheme : "") + ".+";
                                            if (headerValues != null) {
                                                headerValues.add(headerValue);
                                            } else {
                                                headerRequirements.put(parameterName, new HashSet<>(Collections.singletonList(headerValue)));
                                            }
                                            break;
                                        }
                                    }
                                    break;
                                }
                            }

                        }
                    }
                }
            }
        }
    }

    private BiConsumer<String, MediaType> handleRequestBody(OpenAPIDefinition openAPIDefinition, OpenAPI openAPI, String path, Pair<String, Operation> methodOperationPair, Boolean required, List<LogEntry> logEntries) {
        return (contentType, mediaType) -> {
            HttpRequest httpRequest = createHttpRequest(openAPIDefinition, openAPI, path, methodOperationPair);
            if (contentType.equals("multipart/form-data")) {
                try {
                    // No Content-Type header matcher is added here: the MultipartBody matcher itself
                    // only matches genuine multipart/form-data bodies, and when the schema declares no
                    // required fields we fall back to matching on path and method only (so a request to
                    // the operation still matches even without a multipart body), preserving prior behaviour.
                    org.mockserver.model.MultipartBody multipartBody = buildMultipartBody(mediaType);
                    if (multipartBody != null) {
                        httpRequest.withBody(multipartBody.withOptional(!required));
                    }
                    addRequestMatcher(openAPIDefinition, methodOperationPair, httpRequest, contentType);
                } catch (Throwable throwable) {
                    logEntries.add(
                        new LogEntry()
                            .setLogLevel(ERROR)
                            .setMessageFormat("exception while creating adding request matcher for operation:{}method:{}in open api:{}")
                            .setArguments(methodOperationPair.getRight().getOperationId(), methodOperationPair.getLeft(), openAPIDefinition)
                    );
                }
                return;
            }
            if (!contentType.equals("*/*") && required) {
                // ensure that parameters added to the content type such as charset don't break the matching (but escape special characters in header value so they don't break regex)
                httpRequest.withHeader(CONTENT_TYPE.toString(), contentType.replaceAll("[_ :;.,/\"'?!(){}\\[\\]@<>=\\-+*#$&`|~^%]", "\\\\$0").replaceAll("\\*", ".*") + ".*");
            }
            if (mediaType != null && mediaType.getSchema() != null) {
                Map<String, ParameterStyle> parameterStyle = null;
                if (mediaType.getEncoding() != null) {
                    parameterStyle = new HashMap<>();
                    for (Map.Entry<String, Encoding> encodingEntry : mediaType.getEncoding().entrySet()) {
                        parameterStyle.put(encodingEntry.getKey(), parameterStyle(encodingEntry.getValue().getExplode(), encodingEntry.getValue().getStyle()));
                    }
                }
                try {
                    httpRequest.withBody(jsonSchema(OBJECT_WRITER.writeValueAsString(mediaType.getSchema())).withParameterStyles(parameterStyle).withOptional(!required));
                } catch (Throwable throwable) {
                    logEntries.add(
                        new LogEntry()
                            .setLogLevel(ERROR)
                            .setMessageFormat("exception while creating adding request body{} for operation:{}method:{}in open api:{}")
                            .setArguments(mediaType.getSchema(), methodOperationPair.getRight().getOperationId(), methodOperationPair.getLeft(), openAPIDefinition)
                    );
                }
            }
            try {
                addRequestMatcher(openAPIDefinition, methodOperationPair, httpRequest, contentType);
            } catch (Throwable throwable) {
                logEntries.add(
                    new LogEntry()
                        .setLogLevel(ERROR)
                        .setMessageFormat("exception while creating adding request matcher for operation:{}method:{}in open api:{}")
                        .setArguments(methodOperationPair.getRight().getOperationId(), methodOperationPair.getLeft(), openAPIDefinition)
                );
            }
        };
    }

    /**
     * Builds a {@link org.mockserver.model.MultipartBody} matcher from a multipart/form-data
     * media type schema. Each property declared as {@code required} on the schema becomes a
     * field that must be present in the request (matched with a wildcard value); optional
     * properties are not enforced. Returns {@code null} when no required fields can be derived
     * (in which case matching falls back to path, method and content-type only).
     */
    private org.mockserver.model.MultipartBody buildMultipartBody(MediaType mediaType) {
        if (mediaType == null || mediaType.getSchema() == null) {
            return null;
        }
        Schema<?> schema = mediaType.getSchema();
        List<String> requiredProperties = schema.getRequired();
        if (requiredProperties == null || requiredProperties.isEmpty()) {
            return null;
        }
        List<org.mockserver.model.Parameter> fields = new java.util.ArrayList<>();
        for (String propertyName : requiredProperties) {
            // require the field to be present with any value
            fields.add(new org.mockserver.model.Parameter(string(propertyName), ".*"));
        }
        return new org.mockserver.model.MultipartBody(fields);
    }

    private void addRequestMatcher(OpenAPIDefinition openAPIDefinition, Pair<String, Operation> methodOperationPair, HttpRequest httpRequest, String contentType) {
        httpRequests.add(httpRequest);
        HttpRequestPropertiesMatcher httpRequestPropertiesMatcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        httpRequestPropertiesMatcher.update(httpRequest);
        httpRequestPropertiesMatcher.setControlPlaneMatcher(controlPlaneMatcher);
        int maxUrlOrPathLength = 40;
        int urlOrPathLength = openAPIDefinition.getSpecUrlOrPayload().length();
        String urlOrPath = (urlOrPathLength > maxUrlOrPathLength ? "..." : "") + openAPIDefinition.getSpecUrlOrPayload().substring(urlOrPathLength > maxUrlOrPathLength ? urlOrPathLength - maxUrlOrPathLength : urlOrPathLength);
        httpRequestPropertiesMatcher.setDescription("" +
            " open api" +
            (OpenAPIParser.isSpecUrl(openAPIDefinition.getSpecUrlOrPayload()) ? " \"" + urlOrPath + "\"" : "") +
            (isNotBlank(methodOperationPair.getValue().getOperationId()) ? " operation \"" + methodOperationPair.getValue().getOperationId() + "\"" : "") +
            (isNotBlank(contentType) ? " content-type \"" + contentType + "\"" : "")
        );
        httpRequestPropertiesMatchers.add(httpRequestPropertiesMatcher);
    }

    @Override
    public boolean matches(MatchDifference context, RequestDefinition requestDefinition) {
        boolean result = false;
        // Gate on the expectation's lifecycle (TTL and remaining matches) exactly as every
        // sibling matcher does. The per-operation delegates below are built with
        // update(HttpRequest), not update(Expectation), so their own isActive() is trivially
        // true -- the lifecycle state lives only on this outer matcher's expectation, and if it
        // is not checked here it is checked nowhere on the serving path, leaving expired OpenAPI
        // expectations served forever.
        if (!isActive()) {
            return false;
        }
        if (httpRequestPropertiesMatchers != null && !httpRequestPropertiesMatchers.isEmpty()) {
            for (HttpRequestPropertiesMatcher httpRequestPropertiesMatcher : httpRequestPropertiesMatchers) {
                if (context == null) {
                    if (mockServerLogger.isEnabledForInstance(Level.TRACE) && requestDefinition instanceof HttpRequest) {
                        context = new MatchDifference(configuration.detailedMatchFailures(), requestDefinition);
                    }
                    result = httpRequestPropertiesMatcher.matches(context, requestDefinition);
                } else {
                    MatchDifference singleMatchDifference = new MatchDifference(configuration.detailedMatchFailures(), context.getHttpRequest());
                    // Carry the diagnostic log-suppression flag through to the delegate matcher so an
                    // OpenAPI-backed expectation does not emit match-result events during explainUnmatched
                    // / debugMismatch (the inner matcher would otherwise log against the fresh context).
                    if (context.isSuppressMatchResultLogging()) {
                        singleMatchDifference.suppressMatchResultLogging();
                    }
                    singleMatchDifference.withNotMatchedLogBudget(context.getNotMatchedLogBudget());
                    result = httpRequestPropertiesMatcher.matches(singleMatchDifference, requestDefinition);
                    context.addDifferences(singleMatchDifference.getAllDifferences());
                }
                if (result) {
                    break;
                }
            }
        } else if (httpRequestPropertiesMatchers == null && controlPlaneMatcher) {
            // No per-operation matchers could be derived (a null or blank spec, so apply() never
            // populated the list -- note a spec that fails to PARSE leaves it empty, not null).
            //
            // On the control plane an unpopulated matcher is an empty filter, and matching
            // everything is the intended "no restriction" semantic for clear / retrieve / verify.
            //
            // On the DATA plane it must not match: an expectation that matches every request
            // would hijack the entire server and serve its action for all traffic. Only
            // update(RequestDefinition) marks a matcher control-plane, so an expectation-backed
            // matcher never takes this branch.
            result = true;
        }
        return result;
    }

    @Override
    public String toString() {
        try {
            return TO_STRING_OBJECT_WRITER
                .writeValueAsString(openAPIDefinition);
        } catch (Exception e) {
            return super.toString();
        }
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
        HttpRequestsPropertiesMatcher that = (HttpRequestsPropertiesMatcher) o;
        return Objects.equals(openAPIDefinition, that.openAPIDefinition);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), openAPIDefinition);
        }
        return hashCode;
    }
}

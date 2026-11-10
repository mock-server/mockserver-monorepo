package org.mockserver.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.*;

/**
 * Masks sensitive data in recorded expectations before they are written to fixture files.
 * <p>
 * Operates on copies: the live event log is never mutated. Header values for a configurable
 * set of header names are replaced with a placeholder ({@value REDACTED_PLACEHOLDER}).
 * <p>
 * Default sensitive headers: {@code Authorization}, {@code x-api-key}, {@code api-key},
 * {@code Cookie}, {@code Set-Cookie}, {@code Proxy-Authorization}.
 */
public class FixtureRedactor {

    public static final String REDACTED_PLACEHOLDER = "***REDACTED***";

    /**
     * Placeholder substituted for an entire body that has body-field redaction
     * configured but cannot be parsed to locate those fields. Failing closed here
     * prevents a credential hiding in an unparseable payload from leaking into a
     * fixture file.
     */
    public static final String UNPARSEABLE_BODY_PLACEHOLDER = "<redacted: body could not be parsed for field redaction>";

    private static final Set<String> DEFAULT_SENSITIVE_HEADERS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        DEFAULT_SENSITIVE_HEADERS.add("Authorization");
        DEFAULT_SENSITIVE_HEADERS.add("x-api-key");
        DEFAULT_SENSITIVE_HEADERS.add("api-key");
        DEFAULT_SENSITIVE_HEADERS.add("Cookie");
        DEFAULT_SENSITIVE_HEADERS.add("Set-Cookie");
        DEFAULT_SENSITIVE_HEADERS.add("Proxy-Authorization");
    }

    private static final Set<String> DEFAULT_SENSITIVE_QUERY_PARAMS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        // Credentials commonly carried in the query string by LLM / cloud APIs:
        // Gemini uses ?key=<API_KEY>; AWS SigV4 presigned requests put the
        // signature and session token in the query string.
        DEFAULT_SENSITIVE_QUERY_PARAMS.add("key");
        DEFAULT_SENSITIVE_QUERY_PARAMS.add("api_key");
        DEFAULT_SENSITIVE_QUERY_PARAMS.add("apikey");
        DEFAULT_SENSITIVE_QUERY_PARAMS.add("access_token");
        DEFAULT_SENSITIVE_QUERY_PARAMS.add("token");
        DEFAULT_SENSITIVE_QUERY_PARAMS.add("signature");
        DEFAULT_SENSITIVE_QUERY_PARAMS.add("x-amz-signature");
        DEFAULT_SENSITIVE_QUERY_PARAMS.add("x-amz-security-token");
        DEFAULT_SENSITIVE_QUERY_PARAMS.add("sig");
    }

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    // Field values found in large bodies, so each render of a retained log entry (every dashboard update) does not
    // decode and parse its bodies again to find them. Weak keys compare by identity and go with the body, whose
    // content never changes once built; a kept list is used only for the field names and Content-Type it was found
    // under, so it never under-scrubs after either changes.
    static final int KEPT_BODY_VALUES_MIN_CHARACTERS = 64 * 1024;
    private static final Cache<Body<?>, KeptBodyValues> KEPT_BODY_VALUES = CacheBuilder.newBuilder()
        .weakKeys()
        .softValues()
        .maximumWeight(4L * 1024 * 1024)
        .weigher((Body<?> body, KeptBodyValues kept) -> kept.characters)
        .build();

    /**
     * The default sensitive header names (case-insensitive), as an unmodifiable
     * set, so callers can reuse them when constructing a redactor with additional
     * body fields without re-declaring the list.
     */
    public static Set<String> defaultSensitiveHeaders() {
        return Collections.unmodifiableSet(DEFAULT_SENSITIVE_HEADERS);
    }

    /**
     * The default sensitive query-string parameter names (case-insensitive), as an
     * unmodifiable set, so callers can reuse them when constructing a redactor.
     */
    public static Set<String> defaultSensitiveQueryParams() {
        return Collections.unmodifiableSet(DEFAULT_SENSITIVE_QUERY_PARAMS);
    }

    private final Set<String> sensitiveHeaders;
    private final Set<String> sensitiveBodyFields;
    private final Set<String> sensitiveQueryParams;

    /**
     * Create a redactor with the default sensitive header list and no body-field
     * redaction.
     */
    public FixtureRedactor() {
        this.sensitiveHeaders = DEFAULT_SENSITIVE_HEADERS;
        this.sensitiveBodyFields = Collections.emptySet();
        this.sensitiveQueryParams = DEFAULT_SENSITIVE_QUERY_PARAMS;
    }

    /**
     * Create a redactor with a custom sensitive header list and no body-field
     * redaction.
     *
     * @param sensitiveHeaders header names to redact (case-insensitive)
     */
    public FixtureRedactor(Collection<String> sensitiveHeaders) {
        this(sensitiveHeaders, Collections.emptyList());
    }

    /**
     * Create a redactor with custom sensitive headers and JSON body field names.
     * Body fields are matched case-insensitively at any depth of a JSON
     * request/response body; their values are replaced with the placeholder.
     *
     * @param sensitiveHeaders    header names to redact (case-insensitive)
     * @param sensitiveBodyFields JSON field names to redact in bodies (case-insensitive)
     */
    public FixtureRedactor(Collection<String> sensitiveHeaders, Collection<String> sensitiveBodyFields) {
        this(sensitiveHeaders, sensitiveBodyFields, DEFAULT_SENSITIVE_QUERY_PARAMS);
    }

    /**
     * Create a redactor with custom sensitive headers, JSON body field names and
     * query-string parameter names. Query parameters are matched case-insensitively;
     * their values are replaced with the placeholder.
     *
     * @param sensitiveHeaders     header names to redact (case-insensitive)
     * @param sensitiveBodyFields  JSON field names to redact in bodies (case-insensitive)
     * @param sensitiveQueryParams query-string parameter names to redact (case-insensitive)
     */
    public FixtureRedactor(Collection<String> sensitiveHeaders, Collection<String> sensitiveBodyFields, Collection<String> sensitiveQueryParams) {
        this.sensitiveHeaders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (sensitiveHeaders != null) {
            this.sensitiveHeaders.addAll(sensitiveHeaders);
        }
        this.sensitiveBodyFields = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (sensitiveBodyFields != null) {
            this.sensitiveBodyFields.addAll(sensitiveBodyFields);
        }
        this.sensitiveQueryParams = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (sensitiveQueryParams != null) {
            this.sensitiveQueryParams.addAll(sensitiveQueryParams);
        }
    }

    /**
     * Redact sensitive headers in an array of expectations. Returns new Expectation objects;
     * the originals are not modified.
     * <p>
     * The {@code Times} / {@code TimeToLive} of the result default to unlimited and the
     * expectation {@code id} is dropped — appropriate for the fixture export/import use
     * case where redacted expectations are re-imported as fresh, unlimited mocks. Use
     * {@link #redact(Expectation[], boolean)} with {@code preserveConstraints=true} to keep
     * the original replay constraints and id (e.g. on the recorded-expectation path).
     *
     * @param expectations the expectations to redact
     * @return new expectations with sensitive header values replaced
     */
    public Expectation[] redact(Expectation[] expectations) {
        return redact(expectations, false);
    }

    /**
     * Redact sensitive headers in an array of expectations. Returns new Expectation objects;
     * the originals are not modified.
     *
     * @param expectations        the expectations to redact
     * @param preserveConstraints when {@code true}, copy {@code Times}, {@code TimeToLive},
     *                            {@code priority} and {@code id} from each source expectation
     *                            into its redacted result; when {@code false}, default to
     *                            unlimited {@code Times} / {@code TimeToLive} and drop the id
     *                            (original fixture export/import behaviour)
     * @return new expectations with sensitive header values replaced
     */
    public Expectation[] redact(Expectation[] expectations, boolean preserveConstraints) {
        if (expectations == null) {
            return new Expectation[0];
        }
        Expectation[] result = new Expectation[expectations.length];
        for (int i = 0; i < expectations.length; i++) {
            result[i] = redactExpectation(expectations[i], preserveConstraints);
        }
        return result;
    }

    private Expectation redactExpectation(Expectation expectation) {
        return redactExpectation(expectation, false);
    }

    private Expectation redactExpectation(Expectation expectation, boolean preserveConstraints) {
        RequestDefinition requestDef = expectation.getHttpRequest();
        HttpResponse response = expectation.getHttpResponse();
        HttpSseResponse sseResponse = expectation.getHttpSseResponse();

        RequestDefinition redactedRequestDef = requestDef;
        if (requestDef instanceof HttpRequest) {
            redactedRequestDef = redactRequest((HttpRequest) requestDef);
        }
        HttpResponse redactedResponse = response != null ? redactResponse(response) : null;
        HttpSseResponse redactedSseResponse = sseResponse != null ? redactSseResponse(sseResponse) : null;

        Expectation result = new Expectation(
            redactedRequestDef,
            preserveConstraints ? expectation.getTimes() : Times.unlimited(),
            preserveConstraints ? expectation.getTimeToLive() : TimeToLive.unlimited(),
            expectation.getPriority()
        );

        if (preserveConstraints && expectation.getId() != null) {
            result.withId(expectation.getId());
        }

        // Scenario state carries no sensitive data — it is matching metadata (e.g. Pact
        // provider-state preconditions). Preserve it across redaction so a state-gated
        // expectation keeps gating after import.
        result
            .withScenarioName(expectation.getScenarioName())
            .withScenarioState(expectation.getScenarioState())
            .withNewScenarioState(expectation.getNewScenarioState());

        if (redactedSseResponse != null) {
            result.thenRespondWithSse(redactedSseResponse);
        } else if (redactedResponse != null) {
            result.thenRespond(redactedResponse);
        } else if (response != null) {
            result.thenRespond(response);
        } else if (sseResponse != null) {
            result.thenRespondWithSse(sseResponse);
        }

        // Preserve a multi-response (SEQUENTIAL/WEIGHTED/SWITCH/RANDOM) response list —
        // e.g. produced by recorded-expectation consolidation — redacting each response.
        // Without this the single-response branch above would silently drop the list.
        List<HttpResponse> responses = expectation.getHttpResponses();
        if (responses != null && !responses.isEmpty()) {
            List<HttpResponse> redactedResponses = new ArrayList<>(responses.size());
            for (HttpResponse eachResponse : responses) {
                redactedResponses.add(eachResponse != null ? redactResponse(eachResponse) : null);
            }
            result.thenRespond(redactedResponses);
            if (expectation.getResponseMode() != null) {
                result.withResponseMode(expectation.getResponseMode());
            }
            if (expectation.getResponseWeights() != null) {
                result.withResponseWeights(expectation.getResponseWeights());
            }
            if (expectation.getSwitchAfter() != null) {
                result.withSwitchAfter(expectation.getSwitchAfter());
            }
        }

        return result;
    }

    /**
     * Redact sensitive headers (and configured JSON body fields) in a single
     * request definition, returning a redacted clone. The original is never
     * mutated. Non-{@link HttpRequest} request definitions (e.g. OpenAPI
     * definitions) are returned unchanged.
     * <p>
     * Used by the live event-log / dashboard redaction path so the masked copy
     * is shown without affecting verification, which reads the unredacted
     * request directly.
     *
     * @param requestDefinition the request to redact (may be {@code null})
     * @return a redacted clone, or the original for null / non-HttpRequest inputs
     */
    public RequestDefinition redactRequestDefinition(RequestDefinition requestDefinition) {
        if (requestDefinition instanceof HttpRequest) {
            return redactRequest((HttpRequest) requestDefinition);
        }
        return requestDefinition;
    }

    /**
     * Redact sensitive headers (and configured JSON body fields) in a single
     * response, returning a redacted clone. The original is never mutated.
     *
     * @param response the response to redact (may be {@code null})
     * @return a redacted clone, or {@code null} when {@code response} is null
     */
    public HttpResponse redactResponseObject(HttpResponse response) {
        if (response == null) {
            return null;
        }
        return redactResponse(response);
    }

    private HttpRequest redactRequest(HttpRequest request) {
        HttpRequest redacted = request.clone();
        if (redacted.getHeaderList() != null) {
            Headers headers = new Headers();
            for (Header header : redacted.getHeaderList()) {
                String name = header.getName().getValue();
                if (sensitiveHeaders.contains(name)) {
                    headers.withEntry(new Header(name, REDACTED_PLACEHOLDER));
                } else {
                    headers.withEntry(header);
                }
            }
            redacted.withHeaders(headers);
        }
        if (sensitiveHeaders.contains("Cookie") && !redacted.getCookieList().isEmpty()) {
            redacted.withCookies(redactCookies(redacted.getCookieList()));
        }
        redactQueryStringIfNeeded(redacted);
        redactBodyIfNeeded(redacted.getBody(), request.getFirstHeader("Content-Type"), redacted::withBody);
        return redacted;
    }

    /**
     * Replace the values of any configured sensitive query-string parameters with
     * the placeholder. Credentials such as Gemini's {@code ?key=} or AWS SigV4
     * {@code X-Amz-Signature} / {@code X-Amz-Security-Token} are carried in the
     * query string and would otherwise be written to fixtures unmasked.
     */
    private void redactQueryStringIfNeeded(HttpRequest redacted) {
        List<Parameter> existing = redacted.getQueryStringParameterList();
        if (sensitiveQueryParams.isEmpty() || existing == null || existing.isEmpty()) {
            return;
        }
        Parameters parameters = new Parameters();
        for (Parameter parameter : existing) {
            String name = parameter.getName().getValue();
            if (sensitiveQueryParams.contains(name)) {
                parameters.withEntry(new Parameter(name, REDACTED_PLACEHOLDER));
            } else {
                parameters.withEntry(parameter);
            }
        }
        redacted.withQueryStringParameters(parameters);
    }

    private HttpResponse redactResponse(HttpResponse response) {
        HttpResponse redacted = response.clone();
        if (redacted.getHeaderList() != null) {
            Headers headers = new Headers();
            for (Header header : redacted.getHeaderList()) {
                String name = header.getName().getValue();
                if (sensitiveHeaders.contains(name)) {
                    headers.withEntry(new Header(name, REDACTED_PLACEHOLDER));
                } else {
                    headers.withEntry(header);
                }
            }
            redacted.withHeaders(headers);
        }
        if (sensitiveHeaders.contains("Set-Cookie") && !redacted.getCookieList().isEmpty()) {
            redacted.withCookies(redactCookies(redacted.getCookieList()));
        }
        redactBodyIfNeeded(redacted.getBody(), response.getFirstHeader("Content-Type"), redacted::withBody);
        return redacted;
    }

    /**
     * Shortest value {@link SensitiveValueMatcher#scrub} replaces in free text. A shorter value is still masked in its
     * own field, but searching for it in free text would mostly mask unrelated words and numbers.
     */
    public static final int MIN_SCRUBBED_VALUE_LENGTH = 4;

    /**
     * Shortest bare cookie value {@link SensitiveValueMatcher#scrub} replaces in free text. A {@code Cookie} header routinely
     * carries short preference values ({@code lang=en}, {@code theme=dark}, {@code consent=yes}) beside the
     * session identifier, which is a generated token far longer than this; the whole {@code name=value}
     * pair, and the whole header, are still scrubbed at {@link #MIN_SCRUBBED_VALUE_LENGTH}.
     */
    public static final int MIN_SCRUBBED_COOKIE_VALUE_LENGTH = 8;

    /**
     * The credential values these requests and this response carry in the places this redactor masks, for
     * {@link SensitiveValueMatcher#scrub}bing free text that quotes them, such as a matcher's "found" text: sensitive
     * header values (and the credential after a scheme such as {@code Bearer}), cookie values and sensitive
     * query-string values first, then configured body-field values, each followed by its JSON-escaped form where that
     * differs. Values shorter than {@link #MIN_SCRUBBED_VALUE_LENGTH} (bare cookie values:
     * {@link #MIN_SCRUBBED_COOKIE_VALUE_LENGTH}) or longer than {@link SensitiveValueMatcher#MAX_VALUE_LENGTH}, and the
     * words {@code true}, {@code false} and {@code null}, are left out.
     */
    public List<String> sensitiveValues(RequestDefinition[] requests, HttpResponse response) {
        return sensitiveValues(requests == null ? Collections.emptyList() : Arrays.asList(requests), response == null ? Collections.emptyList() : Collections.singletonList(response));
    }

    /**
     * As {@link #sensitiveValues(RequestDefinition[], HttpResponse)}, for several responses: the header, cookie and
     * query-string values of every request and response come before any body-field value.
     */
    public List<String> sensitiveValues(Collection<RequestDefinition> requests, Collection<HttpResponse> responses) {
        Set<String> values = new LinkedHashSet<>();
        Set<String> cookieValues = new LinkedHashSet<>();
        Set<String> bodyValues = new LinkedHashSet<>();
        if (requests != null) {
            for (RequestDefinition requestDefinition : requests) {
                if (requestDefinition instanceof HttpRequest) {
                    HttpRequest request = (HttpRequest) requestDefinition;
                    addHeaderValues(values, cookieValues, request.getHeaderList());
                    if (sensitiveHeaders.contains("Cookie")) {
                        addCookieValues(cookieValues, request.getCookieList());
                    }
                    List<Parameter> parameters = request.getQueryStringParameterList();
                    if (parameters != null) {
                        for (Parameter parameter : parameters) {
                            if (sensitiveQueryParams.contains(parameter.getName().getValue())) {
                                addAll(values, parameter.getValues());
                            }
                        }
                    }
                    addBodyFieldValues(bodyValues, request.getBody(), request.getFirstHeader("Content-Type"));
                }
            }
        }
        for (HttpResponse response : responses == null ? Collections.<HttpResponse>emptyList() : responses) {
            if (response != null) {
                addHeaderValues(values, cookieValues, response.getHeaderList());
                if (sensitiveHeaders.contains("Set-Cookie")) {
                    addCookieValues(cookieValues, response.getCookieList());
                }
                addBodyFieldValues(bodyValues, response.getBody(), response.getFirstHeader("Content-Type"));
            }
        }
        values.removeIf(value -> value.length() < MIN_SCRUBBED_VALUE_LENGTH);
        cookieValues.removeIf(value -> value.length() < MIN_SCRUBBED_COOKIE_VALUE_LENGTH);
        bodyValues.removeIf(value -> value.length() < MIN_SCRUBBED_VALUE_LENGTH);
        values.addAll(cookieValues);
        values.addAll(bodyValues);
        List<String> ordered = new ArrayList<>(values.size());
        for (String value : values) {
            if (value.length() > SensitiveValueMatcher.MAX_VALUE_LENGTH || value.equals(REDACTED_PLACEHOLDER) || isCommonWord(value)) {
                continue;
            }
            ordered.add(value);
            // a value quoted inside JSON text (e.g. a serialized header map) appears in its escaped form
            String escaped = new String(com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance().quoteAsString(value));
            if (!escaped.equals(value)) {
                ordered.add(escaped);
            }
        }
        return ordered;
    }

    private static boolean isCommonWord(String value) {
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("null");
    }

    private void addHeaderValues(Set<String> values, Set<String> cookieValues, List<Header> headers) {
        if (headers == null) {
            return;
        }
        for (Header header : headers) {
            String name = header.getName().getValue();
            if (!sensitiveHeaders.contains(name)) {
                continue;
            }
            for (NottableString nottableValue : header.getValues()) {
                String value = nottableValue.getValue();
                if (value == null) {
                    continue;
                }
                values.add(value);
                if (name.equalsIgnoreCase("Cookie")) {
                    for (String pair : value.split(";")) {
                        values.add(pair.trim());
                        addAfterEquals(cookieValues, pair);
                    }
                } else if (name.equalsIgnoreCase("Set-Cookie")) {
                    String pair = value.split(";", 2)[0];
                    values.add(pair.trim());
                    addAfterEquals(cookieValues, pair);
                } else {
                    // "Bearer <token>", "Basic <credentials>": the credential alone can be quoted too
                    int space = value.trim().indexOf(' ');
                    if (space > 0) {
                        values.add(value.trim().substring(space + 1).trim());
                    }
                }
            }
        }
    }

    private static void addAfterEquals(Set<String> values, String pair) {
        int equals = pair.indexOf('=');
        if (equals >= 0) {
            values.add(pair.substring(equals + 1).trim());
        }
    }

    private static void addCookieValues(Set<String> values, List<Cookie> cookies) {
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getValue() != null && cookie.getValue().getValue() != null) {
                    values.add(cookie.getValue().getValue());
                }
            }
        }
    }

    private static void addAll(Set<String> values, List<NottableString> nottableStrings) {
        if (nottableStrings != null) {
            for (NottableString nottableString : nottableStrings) {
                if (nottableString.getValue() != null) {
                    values.add(nottableString.getValue());
                }
            }
        }
    }

    private void addBodyFieldValues(Set<String> values, Body<?> body, String contentTypeHeader) {
        if (sensitiveBodyFields.isEmpty() || body == null) {
            return;
        }
        boolean keepable = body instanceof StringBody || body instanceof JsonBody || body instanceof XmlBody || body instanceof BinaryBody;
        KeptBodyValues kept = keepable ? KEPT_BODY_VALUES.getIfPresent(body) : null;
        if (kept != null && kept.fields.equals(new ArrayList<>(sensitiveBodyFields)) && Objects.equals(kept.contentType, contentTypeHeader)) {
            values.addAll(kept.values);
            return;
        }
        String bodyString = bodyText(body, contentTypeHeader);
        if (bodyString == null || bodyString.isEmpty()) {
            return;
        }
        if (!keepable || bodyString.length() < KEPT_BODY_VALUES_MIN_CHARACTERS) {
            addBodyFieldValues(values, bodyString);
            return;
        }
        Set<String> found = new LinkedHashSet<>();
        addBodyFieldValues(found, bodyString);
        KEPT_BODY_VALUES.put(body, new KeptBodyValues(new ArrayList<>(sensitiveBodyFields), contentTypeHeader, found));
        values.addAll(found);
    }

    private void addBodyFieldValues(Set<String> values, String bodyString) {
        if (!mentionsSensitiveBodyField(bodyString)) {
            return;
        }
        List<String> documents = new ArrayList<>();
        if (isServerSentEventStream(bodyString)) {
            for (String line : bodyString.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("data:")) {
                    documents.add(trimmed.substring("data:".length()).trim());
                }
            }
        } else {
            documents.add(bodyString);
        }
        for (String document : documents) {
            try {
                collectSensitiveFieldValues(OBJECT_MAPPER.readTree(document), false, values);
            } catch (Exception notJson) {
                // an unparseable body is replaced whole by redactBodyIfNeeded; there are no field values to find
            }
        }
    }

    private static final class KeptBodyValues {
        private final List<String> fields;
        private final String contentType;
        private final List<String> values;
        private final int characters;

        private KeptBodyValues(List<String> fields, String contentType, Collection<String> values) {
            this.fields = fields;
            this.contentType = contentType;
            this.values = Collections.unmodifiableList(new ArrayList<>(values));
            int characters = 64;
            for (String value : values) {
                characters += value.length();
            }
            this.characters = characters;
        }
    }

    private void collectSensitiveFieldValues(JsonNode node, boolean insideSensitiveField, Set<String> values) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                boolean sensitive = insideSensitiveField || sensitiveBodyFields.contains(field.getKey());
                if (sensitive && !insideSensitiveField && !field.getValue().isValueNode()) {
                    // the whole sensitive object or array as text, once: its leaves are collected below, so the text of
                    // every nested level would only repeat them (depth x size)
                    String text = compactTextWithin(field.getValue(), SensitiveValueMatcher.MAX_VALUE_LENGTH);
                    if (text != null) {
                        values.add(text);
                    }
                }
                collectSensitiveFieldValues(field.getValue(), sensitive, values);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectSensitiveFieldValues(child, insideSensitiveField, values);
            }
        } else if (insideSensitiveField && !node.isNull()) {
            values.add(node.asText());
        }
    }

    /**
     * {@code node} as compact JSON (as {@link JsonNode#toString()} writes it), or {@code null} if that is longer than
     * {@code limit} characters, found without writing more than that.
     */
    private static String compactTextWithin(JsonNode node, int limit) {
        java.io.StringWriter text = new java.io.StringWriter() {
            @Override
            public void write(char[] characters, int offset, int length) {
                if (getBuffer().length() + length > limit) {
                    throw TOO_LONG;
                }
                super.write(characters, offset, length);
            }

            @Override
            public void write(String string, int offset, int length) {
                if (getBuffer().length() + length > limit) {
                    throw TOO_LONG;
                }
                super.write(string, offset, length);
            }

            @Override
            public void write(int character) {
                if (getBuffer().length() + 1 > limit) {
                    throw TOO_LONG;
                }
                super.write(character);
            }
        };
        try {
            COMPACT_WRITER.writeValue(text, node);
            return text.toString();
        } catch (TooLong | java.io.IOException tooLong) {
            return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectWriter COMPACT_WRITER = new ObjectMapper().writer();

    private static final TooLong TOO_LONG = new TooLong();

    private static final class TooLong extends RuntimeException {
        private TooLong() {
            super(null, null, false, false);
        }
    }

    /**
     * Mask the parsed cookie values as well as the Cookie / Set-Cookie headers they came from: the
     * cookie list is serialized separately ({@code cookies}, HAR) and the cURL form rebuilds a
     * {@code Cookie} header from it, so masking only the header left every value readable there.
     */
    private static Cookies redactCookies(List<Cookie> cookies) {
        List<Cookie> masked = new ArrayList<>(cookies.size());
        for (Cookie cookie : cookies) {
            masked.add(new Cookie(cookie.getName(), REDACTED_PLACEHOLDER));
        }
        return new Cookies(masked);
    }

    /**
     * If body-field redaction is configured, redact matching fields in
     * {@code body} and apply the result via {@code setter}. No-op when no
     * body fields are configured or the body is absent/empty.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>A single JSON document (object or array) — redact configured fields at
     *       any depth and re-emit. A scalar JSON value cannot carry a named field,
     *       so it is left unchanged.</li>
     *   <li>An SSE / streamed body (one or more {@code data:} lines) — redact the
     *       configured fields inside each {@code data:} JSON payload individually
     *       and re-emit the SSE structure. Non-JSON {@code data:} markers such as
     *       {@code [DONE]} are left intact.</li>
     *   <li>An unstructured body (plain text, HTML, decoded binary, …) — left
     *       unchanged, because there are no named fields to redact. The single
     *       exception is when the raw text still mentions a configured field name
     *       (e.g. a truncated/malformed JSON payload containing {@code "api_key":…}
     *       that our parser could not reach): there we <b>fail closed</b> and
     *       replace the whole body with {@value #UNPARSEABLE_BODY_PLACEHOLDER}
     *       rather than risk leaking a credential we could not mask structurally.</li>
     * </ol>
     * Note: the redacted body is re-applied as a string body. For recorded
     * fixtures (captured traffic) bodies are already string bodies, so this does
     * not change match semantics.
     */
    private void redactBodyIfNeeded(Body<?> body, String contentTypeHeader, java.util.function.Consumer<String> setter) {
        if (sensitiveBodyFields.isEmpty() || body == null) {
            return;
        }
        String bodyString = bodyText(body, contentTypeHeader);
        if (bodyString == null || bodyString.isEmpty()) {
            return;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(bodyString);
            if (root != null && (root.isObject() || root.isArray())) {
                redactJsonNode(root);
                setter.accept(OBJECT_MAPPER.writeValueAsString(root));
            }
            // a scalar JSON value (string / number / boolean / null) parsed cleanly
            // and cannot contain a named field — nothing to redact, leave unchanged
            return;
        } catch (Exception notSingleJsonDocument) {
            // fall through: an SSE stream or a genuinely unparseable body
        }
        if (isServerSentEventStream(bodyString)) {
            setter.accept(redactSseBody(bodyString));
            return;
        }
        // Neither a JSON document nor an SSE stream. We can only redact NAMED fields,
        // and an unstructured body (plain text, HTML, decoded binary, …) has none — so
        // it is normally left unchanged. The one exception is a body that LOOKS like it
        // should have been parseable structured data carrying a configured secret field
        // (e.g. a truncated/malformed JSON payload still containing "api_key":"…"): there
        // the field is present but our parser could not reach it to mask it, so we fail
        // closed and replace the whole body rather than leak the raw secret.
        if (mentionsSensitiveBodyField(bodyString)) {
            setter.accept(UNPARSEABLE_BODY_PLACEHOLDER);
        }
    }

    /**
     * The body as the text a reader sees: a binary body sent with no Content-Type as lenient UTF-8 (base64
     * would hide its field names and values from redaction), anything else read without caching, because
     * the clone shares the original's body, which may be retained in the event log.
     */
    private static String bodyText(Body<?> body, String contentTypeHeader) {
        return body instanceof BinaryBody ? BinaryBody.matchableString(body, contentTypeHeader) : body.toStringWithoutCaching();
    }

    /**
     * True if the raw body text contains any configured sensitive field name
     * (case-insensitive). Used to decide whether an unparseable body might be hiding
     * a secret we failed to mask structurally — if no configured field name even
     * appears, there is nothing to fail closed over and the body is left intact.
     */
    private boolean mentionsSensitiveBodyField(String body) {
        String lower = body.toLowerCase();
        for (String field : sensitiveBodyFields) {
            if (lower.contains(field.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * An SSE / streamed body is recognised by at least one line beginning with the
     * {@code data:} field name (the carriage return of a {@code \r\n} separator is
     * tolerated). A JSON document never reaches this check because it is handled by
     * the single-document path first.
     */
    private boolean isServerSentEventStream(String body) {
        for (String segment : body.split("\n", -1)) {
            String line = segment.endsWith("\r") ? segment.substring(0, segment.length() - 1) : segment;
            if (line.startsWith("data:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Redact configured fields within each {@code data:} JSON payload of an SSE
     * body, preserving the line / event structure (including {@code \r\n}
     * separators and non-{@code data:} lines such as {@code event:} / {@code id:}).
     */
    private String redactSseBody(String body) {
        String[] segments = body.split("\n", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean trailingCr = segment.endsWith("\r");
            String line = trailingCr ? segment.substring(0, segment.length() - 1) : segment;
            if (line.startsWith("data:")) {
                String afterPrefix = line.substring("data:".length());
                String space = afterPrefix.startsWith(" ") ? " " : "";
                String payload = afterPrefix.substring(space.length());
                line = "data:" + space + redactJsonPayloadOrSelf(payload);
            }
            segments[i] = trailingCr ? line + "\r" : line;
        }
        return String.join("\n", segments);
    }

    /**
     * Redact configured fields in a single SSE {@code data:} payload when it is a
     * JSON object or array. A payload we cannot parse as a JSON object/array but
     * which still mentions a configured field name (e.g. a truncated event, or one
     * JSON event split across multiple {@code data:} lines) is <b>failed closed</b>
     * to the placeholder — the same rationale as the non-SSE body path — rather than
     * emitted raw. Any other non-JSON marker ({@code [DONE]}, empty) is left intact.
     */
    private String redactJsonPayloadOrSelf(String payload) {
        String trimmed = payload.trim();
        if (trimmed.isEmpty()) {
            return payload;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(trimmed);
            if (node != null && (node.isObject() || node.isArray())) {
                redactJsonNode(node);
                return OBJECT_MAPPER.writeValueAsString(node);
            }
        } catch (Exception notJson) {
            // fall through: a non-JSON data marker or an unparseable chunk
        }
        if (mentionsSensitiveBodyField(payload)) {
            return UNPARSEABLE_BODY_PLACEHOLDER;
        }
        return payload;
    }

    /**
     * Recursively replace the value of any field whose name is in
     * {@link #sensitiveBodyFields} with the placeholder.
     */
    private void redactJsonNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<String> names = object.fieldNames();
            List<String> toRedact = new ArrayList<>();
            while (names.hasNext()) {
                String name = names.next();
                if (sensitiveBodyFields.contains(name)) {
                    toRedact.add(name);
                } else {
                    redactJsonNode(object.get(name));
                }
            }
            for (String name : toRedact) {
                object.put(name, REDACTED_PLACEHOLDER);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                redactJsonNode(child);
            }
        }
    }

    private HttpSseResponse redactSseResponse(HttpSseResponse sseResponse) {
        HttpSseResponse redacted = HttpSseResponse.sseResponse();
        redacted.withStatusCode(sseResponse.getStatusCode());
        redacted.withCloseConnection(sseResponse.getCloseConnection());
        redacted.withTemplateType(sseResponse.getTemplateType());
        if (sseResponse.getEvents() != null) {
            redacted.withEvents(sensitiveBodyFields.isEmpty() ? sseResponse.getEvents() : redactSseEvents(sseResponse.getEvents()));
        }
        if (sseResponse.getHeaders() != null) {
            Headers headers = new Headers();
            for (Header header : sseResponse.getHeaders().getEntries()) {
                String name = header.getName().getValue();
                if (sensitiveHeaders.contains(name)) {
                    headers.withEntry(new Header(name, REDACTED_PLACEHOLDER));
                } else {
                    headers.withEntry(header);
                }
            }
            redacted.withHeaders(headers);
        }
        return redacted;
    }

    /**
     * Redact configured body fields in each event's data the same way a static SSE body's
     * {@code data:} lines are, building new events so the source expectation is never mutated.
     */
    private List<SseEvent> redactSseEvents(List<SseEvent> events) {
        List<SseEvent> redactedEvents = new ArrayList<>(events.size());
        for (SseEvent event : events) {
            if (event == null || (event.getData() == null && event.getComment() == null)) {
                redactedEvents.add(event);
            } else {
                // a comment is free text, so one that names a sensitive field is replaced whole
                String comment = event.getComment();
                redactedEvents.add(SseEvent.sseEvent()
                    .withEvent(event.getEvent())
                    .withData(event.getData() != null ? redactJsonPayloadOrSelf(event.getData()) : null)
                    .withId(event.getId())
                    .withRetry(event.getRetry())
                    .withComment(comment != null && mentionsSensitiveBodyField(comment) ? UNPARSEABLE_BODY_PLACEHOLDER : comment)
                    .withDelay(event.getDelay()));
            }
        }
        return redactedEvents;
    }
}

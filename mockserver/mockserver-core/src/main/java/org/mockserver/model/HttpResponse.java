package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.Multimap;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpStatusCode.BAD_GATEWAY_502;
import static org.mockserver.model.HttpStatusCode.NOT_FOUND_404;
import static org.mockserver.model.HttpStatusCode.OK_200;
import static org.mockserver.model.NottableString.string;

/**
 * @author jamesdbloom
 */
@SuppressWarnings("rawtypes")
public class HttpResponse extends Action<HttpResponse> implements HttpMessage<HttpResponse, BodyWithContentType> {
    private int hashCode;
    private Integer statusCode;
    private String statusCodeRange;
    private String reasonPhrase;
    private BodyWithContentType body;
    private String generateFromSchema;
    private Headers headers;
    private Headers trailers;
    private Cookies cookies;
    private ConnectionOptions connectionOptions;
    private RecoverAfter recoverAfter;
    private Integer streamId = null;
    private Timing timing;
    private transient StreamingBody streamingBody;

    /**
     * Static builder to create a response.
     */
    public static HttpResponse response() {
        return new HttpResponse();
    }

    /**
     * Static builder to create a response with a 200 status code and the string response body.
     *
     * @param body a string
     */
    public static HttpResponse response(String body) {
        return new HttpResponse().withStatusCode(OK_200.code()).withReasonPhrase(OK_200.reasonPhrase()).withBody(body);
    }

    /**
     * Static builder to create a not found response.
     */
    public static HttpResponse notFoundResponse() {
        return new HttpResponse().withStatusCode(NOT_FOUND_404.code()).withReasonPhrase(NOT_FOUND_404.reasonPhrase());
    }

    /**
     * Static builder to create a bad gateway response.
     */
    public static HttpResponse badGatewayResponse() {
        return new HttpResponse().withStatusCode(BAD_GATEWAY_502.code()).withReasonPhrase(BAD_GATEWAY_502.reasonPhrase());
    }

    /**
     * The status code to return, such as 200, 404, the status code specified
     * here will result in the default status message for this status code for
     * example for 200 the status message "OK" is used
     *
     * @param statusCode an integer such as 200 or 404
     */
    public HttpResponse withStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
        this.hashCode = 0;
        return this;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * A status-code range or numeric-operator expression used <b>only when verifying</b> a recorded
     * response (it is never written to the wire when serving). When set it drives status matching in
     * place of an exact {@link #withStatusCode(Integer)} comparison. Supported forms:
     * <ul>
     *   <li>class range — a single digit followed by {@code XX} (case-insensitive), e.g. {@code "2XX"}
     *       matches {@code 200..299}, {@code "5xx"} matches {@code 500..599};</li>
     *   <li>numeric operator — a leading comparison operator and a number, e.g. {@code ">= 400"},
     *       {@code "> 200"}, {@code "< 300"}, {@code "<= 204"}, {@code "== 201"}.</li>
     * </ul>
     * When {@code statusCodeRange} is null/blank the exact {@link #getStatusCode()} value is used,
     * preserving the historical exact-match behaviour. When both are set the range takes precedence
     * for matching and the exact {@link #getStatusCode()} is not consulted.
     *
     * @param statusCodeRange a status-code range ({@code "2XX"}) or operator ({@code ">= 400"}) expression
     */
    public HttpResponse withStatusCodeRange(String statusCodeRange) {
        this.statusCodeRange = statusCodeRange;
        this.hashCode = 0;
        return this;
    }

    public String getStatusCodeRange() {
        return statusCodeRange;
    }

    /**
     * The reason phrase to return, if no reason code is returned this will
     * be defaulted to the standard reason phrase for the statusCode,
     * i.e. for a statusCode of 200 the standard reason phrase is "OK"
     *
     * @param reasonPhrase an string such as "Not Found" or "OK"
     */
    public HttpResponse withReasonPhrase(String reasonPhrase) {
        this.reasonPhrase = reasonPhrase;
        this.hashCode = 0;
        return this;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    /**
     * Set response body to return as a string response body. The character set will be determined by the Content-Type header
     * on the response. To force the character set, use {@link #withBody(String, Charset)}.
     *
     * @param body a string
     */
    public HttpResponse withBody(String body) {
        if (body != null) {
            this.body = new StringBody(body);
            this.hashCode = 0;
        }
        return this;
    }

    /**
     * Set response body to return a string response body with the specified encoding. <b>Note:</b> The character set of the
     * response will be forced to the specified charset, even if the Content-Type header specifies otherwise.
     *
     * @param body    a string
     * @param charset character set the string will be encoded in
     */
    public HttpResponse withBody(String body, Charset charset) {
        if (body != null) {
            this.body = new StringBody(body, charset);
            this.hashCode = 0;
        }
        return this;
    }

    /**
     * Set response body to return a string response body with the specified encoding. <b>Note:</b> The character set of the
     * response will be forced to the specified charset, even if the Content-Type header specifies otherwise.
     *
     * @param body        a string
     * @param contentType media type, if charset is included this will be used for encoding string
     */
    public HttpResponse withBody(String body, MediaType contentType) {
        if (body != null) {
            this.body = new StringBody(body, contentType);
            this.hashCode = 0;
        }
        return this;
    }

    /**
     * Set response body to return as binary such as a pdf or image
     *
     * @param body a byte array
     */
    public HttpResponse withBody(byte[] body) {
        this.body = new BinaryBody(body);
        this.hashCode = 0;
        return this;
    }

    /**
     * Set the body to return for example:
     * <p/>
     * string body:
     * - exact("<html><head/><body><div>a simple string body</div></body></html>");
     * <p/>
     * or
     * <p/>
     * - new StringBody("<html><head/><body><div>a simple string body</div></body></html>")
     * <p/>
     * binary body:
     * - binary(IOUtils.readFully(getClass().getClassLoader().getResourceAsStream("example.pdf"), 1024));
     * <p/>
     * or
     * <p/>
     * - new BinaryBody(IOUtils.readFully(getClass().getClassLoader().getResourceAsStream("example.pdf"), 1024));
     *
     * @param body an instance of one of the Body subclasses including StringBody or BinaryBody
     */
    public HttpResponse withBody(BodyWithContentType body) {
        this.body = body;
        this.hashCode = 0;
        return this;
    }

    public HttpResponse withBodyFromFile(String filePath) {
        this.body = new FileBody(filePath);
        this.hashCode = 0;
        return this;
    }

    public HttpResponse withBodyFromFile(String filePath, MediaType contentType) {
        this.body = new FileBody(filePath, contentType);
        this.hashCode = 0;
        return this;
    }

    public BodyWithContentType getBody() {
        return body;
    }

    @JsonIgnore
    public byte[] getBodyAsRawBytes() {
        return this.body != null ? this.body.getRawBytes() : new byte[0];
    }

    @JsonIgnore
    public String getBodyAsString() {
        if (body != null) {
            return body.toString();
        } else {
            return null;
        }
    }

    /**
     * Provide an inline <a href="https://json-schema.org">JSON Schema</a> (a plain JSON Schema object,
     * <b>not</b> a full OpenAPI document) from which a schema-valid response body is generated at
     * response time. The same example-generation engine used for OpenAPI responses is reused, so the
     * generated body honours {@code type}, {@code required}, {@code enum}, {@code default}, arrays and
     * nested objects.
     * <p>
     * This is additive: it only takes effect when the response has no explicit body. An explicit body
     * (set via any {@code withBody(...)} overload) always wins.
     *
     * @param generateFromSchema an inline JSON Schema, e.g.
     *                           {@code "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}}}"}
     */
    public HttpResponse withGenerateFromSchema(String generateFromSchema) {
        this.generateFromSchema = generateFromSchema;
        this.hashCode = 0;
        return this;
    }

    public String getGenerateFromSchema() {
        return generateFromSchema;
    }

    public Headers getHeaders() {
        return this.headers;
    }

    private Headers getOrCreateHeaders() {
        if (this.headers == null) {
            this.headers = new Headers();
            this.hashCode = 0;
        }
        return this.headers;
    }

    public HttpResponse withHeaders(Headers headers) {
        if (headers == null || headers.isEmpty()) {
            this.headers = null;
        } else {
            this.headers = headers;
        }
        this.hashCode = 0;
        return this;
    }

    /**
     * The headers to return as a list of Header objects
     *
     * @param headers a list of Header objects
     */
    public HttpResponse withHeaders(List<Header> headers) {
        getOrCreateHeaders().withEntries(headers);
        this.hashCode = 0;
        return this;
    }

    /**
     * The headers to return as a varargs of Header objects
     *
     * @param headers varargs of Header objects
     */
    public HttpResponse withHeaders(Header... headers) {
        getOrCreateHeaders().withEntries(headers);
        this.hashCode = 0;
        return this;
    }

    /**
     * Add a header to return as a Header object, if a header with
     * the same name already exists this will NOT be modified but
     * two headers will exist
     *
     * @param header a Header object
     */
    public HttpResponse withHeader(Header header) {
        getOrCreateHeaders().withEntry(header);
        this.hashCode = 0;
        return this;
    }

    /**
     * Add a header to return as a Header object, if a header with
     * the same name already exists this will NOT be modified but
     * two headers will exist
     *
     * @param name   the header name
     * @param values the header values
     */
    public HttpResponse withHeader(String name, String... values) {
        if (values.length == 0) {
            values = new String[]{".*"};
        }
        getOrCreateHeaders().withEntry(name, values);
        this.hashCode = 0;
        return this;
    }

    /**
     * Add a header to return as a Header object, if a header with
     * the same name already exists this will NOT be modified but
     * two headers will exist
     *
     * @param name   the header name as a NottableString
     * @param values the header values which can be a varags of NottableStrings
     */
    public HttpResponse withHeader(NottableString name, NottableString... values) {
        if (values.length == 0) {
            values = new NottableString[]{string(".*")};
        }
        getOrCreateHeaders().withEntry(header(name, values));
        this.hashCode = 0;
        return this;
    }

    public HttpResponse withContentType(MediaType mediaType) {
        getOrCreateHeaders().withEntry(header(CONTENT_TYPE.toString(), mediaType.toString()));
        this.hashCode = 0;
        return this;
    }

    /**
     * Update header to return as a Header object, if a header with
     * the same name already exists it will be modified
     *
     * @param header a Header object
     */
    public HttpResponse replaceHeader(Header header) {
        getOrCreateHeaders().replaceEntry(header);
        this.hashCode = 0;
        return this;
    }

    /**
     * Update header to return as a Header object, if a header with
     * the same name already exists it will be modified
     *
     * @param name   the header name
     * @param values the header values
     */
    public HttpResponse replaceHeader(String name, String... values) {
        if (values.length == 0) {
            values = new String[]{".*"};
        }
        getOrCreateHeaders().replaceEntry(name, values);
        this.hashCode = 0;
        return this;
    }

    public List<Header> getHeaderList() {
        if (this.headers != null) {
            return this.headers.getEntries();
        } else {
            return Collections.emptyList();
        }
    }

    public Multimap<NottableString, NottableString> getHeaderMultimap() {
        if (this.headers != null) {
            return this.headers.getMultimap();
        } else {
            return null;
        }
    }

    public List<String> getHeader(String name) {
        if (this.headers != null) {
            return this.headers.getValues(name);
        } else {
            return Collections.emptyList();
        }
    }

    public String getFirstHeader(String name) {
        if (this.headers != null) {
            return this.headers.getFirstValue(name);
        } else {
            return "";
        }
    }

    /**
     * Returns true if a header with the specified name has been added
     *
     * @param name the header name
     * @return true if a header has been added with that name otherwise false
     */
    public boolean containsHeader(String name) {
        if (this.headers != null) {
            return this.headers.containsEntry(name);
        } else {
            return false;
        }
    }

    public HttpResponse removeHeader(String name) {
        if (this.headers != null) {
            headers.remove(name);
            this.hashCode = 0;
        }
        return this;
    }

    public HttpResponse removeHeader(NottableString name) {
        if (this.headers != null) {
            headers.remove(name);
            this.hashCode = 0;
        }
        return this;
    }

    /**
     * Returns true if a header with the specified name has been added
     *
     * @param name  the header name
     * @param value the header value
     * @return true if a header has been added with that name otherwise false
     */
    public boolean containsHeader(String name, String value) {
        if (this.headers != null) {
            return this.headers.containsEntry(name, value);
        } else {
            return false;
        }
    }

    public Headers getTrailers() {
        return this.trailers;
    }

    private Headers getOrCreateTrailers() {
        if (this.trailers == null) {
            this.trailers = new Headers();
            this.hashCode = 0;
        }
        return this.trailers;
    }

    /**
     * The trailing headers (HTTP trailers) to send after the response body. Trailers
     * are emitted as protocol-appropriate trailing headers: a chunked trailer section
     * for HTTP/1.1 (with an automatic {@code Trailer} header and chunked transfer-encoding),
     * a trailing HEADERS frame for HTTP/2, and a trailing HEADERS frame for HTTP/3.
     * <p>
     * On HTTP/1.1 trailers force {@code Transfer-Encoding: chunked} (RFC 7230 section 3.3.1 --
     * trailers and a fixed {@code Content-Length} are mutually exclusive); any explicit
     * {@code Content-Length} you set is dropped from a trailer-carrying HTTP/1.1 response.
     * <p>
     * Null or empty trailers result in no trailers being sent (the default behaviour).
     *
     * @param trailers a Headers object holding the trailing header fields
     */
    public HttpResponse withTrailers(Headers trailers) {
        if (trailers == null || trailers.isEmpty()) {
            this.trailers = null;
        } else {
            this.trailers = trailers;
        }
        this.hashCode = 0;
        return this;
    }

    /**
     * The trailing headers to send as a list of Header objects
     *
     * @param trailers a list of Header objects
     */
    public HttpResponse withTrailers(List<Header> trailers) {
        getOrCreateTrailers().withEntries(trailers);
        this.hashCode = 0;
        return this;
    }

    /**
     * The trailing headers to send as a varargs of Header objects
     *
     * @param trailers varargs of Header objects
     */
    public HttpResponse withTrailers(Header... trailers) {
        getOrCreateTrailers().withEntries(trailers);
        this.hashCode = 0;
        return this;
    }

    /**
     * Add a trailing header to send as a Header object, if a trailer with
     * the same name already exists this will NOT be modified but
     * two trailers will exist
     *
     * @param trailer a Header object
     */
    public HttpResponse withTrailer(Header trailer) {
        getOrCreateTrailers().withEntry(trailer);
        this.hashCode = 0;
        return this;
    }

    /**
     * Add a trailing header to send, if a trailer with
     * the same name already exists this will NOT be modified but
     * two trailers will exist
     *
     * @param name   the trailer name
     * @param values the trailer values
     */
    public HttpResponse withTrailer(String name, String... values) {
        if (values.length == 0) {
            values = new String[]{".*"};
        }
        getOrCreateTrailers().withEntry(name, values);
        this.hashCode = 0;
        return this;
    }

    /**
     * Add a trailing header to send, if a trailer with
     * the same name already exists this will NOT be modified but
     * two trailers will exist
     *
     * @param name   the trailer name as a NottableString
     * @param values the trailer values which can be a varags of NottableStrings
     */
    public HttpResponse withTrailer(NottableString name, NottableString... values) {
        if (values.length == 0) {
            values = new NottableString[]{string(".*")};
        }
        getOrCreateTrailers().withEntry(header(name, values));
        this.hashCode = 0;
        return this;
    }

    public List<Header> getTrailerList() {
        if (this.trailers != null) {
            return this.trailers.getEntries();
        } else {
            return Collections.emptyList();
        }
    }

    public Multimap<NottableString, NottableString> getTrailerMultimap() {
        if (this.trailers != null) {
            return this.trailers.getMultimap();
        } else {
            return null;
        }
    }


    public Cookies getCookies() {
        return this.cookies;
    }

    private Cookies getOrCreateCookies() {
        if (this.cookies == null) {
            this.cookies = new Cookies();
            this.hashCode = 0;
        }
        return this.cookies;
    }

    public HttpResponse withCookies(Cookies cookies) {
        if (cookies == null || cookies.isEmpty()) {
            this.cookies = null;
        } else {
            this.cookies = cookies;
        }
        this.hashCode = 0;
        return this;
    }

    /**
     * The cookies to return as Set-Cookie headers as a list of Cookie objects
     *
     * @param cookies a list of Cookie objects
     */
    public HttpResponse withCookies(List<Cookie> cookies) {
        getOrCreateCookies().withEntries(cookies);
        this.hashCode = 0;
        return this;
    }

    /**
     * The cookies to return as Set-Cookie headers as a varargs of Cookie objects
     *
     * @param cookies a varargs of Cookie objects
     */
    public HttpResponse withCookies(Cookie... cookies) {
        getOrCreateCookies().withEntries(cookies);
        this.hashCode = 0;
        return this;
    }

    /**
     * Add cookie to return as Set-Cookie header
     *
     * @param cookie a Cookie object
     */
    public HttpResponse withCookie(Cookie cookie) {
        getOrCreateCookies().withEntry(cookie);
        this.hashCode = 0;
        return this;
    }

    /**
     * Add cookie to return as Set-Cookie header
     *
     * @param name  the cookies name
     * @param value the cookies value
     */
    public HttpResponse withCookie(String name, String value) {
        getOrCreateCookies().withEntry(name, value);
        this.hashCode = 0;
        return this;
    }

    /**
     * <p>
     * Adds one cookie to match on or to not match on using the NottableString, each NottableString can either be a positive matching value,
     * such as string("match"), or a value to not match on, such as not("do not match"), the string values passed to the NottableString
     * can be a plain string or a regex (for more details of the supported regex syntax see <a href="http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html">...</a>)
     * </p>
     *
     * @param name  the cookies name
     * @param value the cookies value
     */
    public HttpResponse withCookie(NottableString name, NottableString value) {
        getOrCreateCookies().withEntry(name, value);
        this.hashCode = 0;
        return this;
    }

    public List<Cookie> getCookieList() {
        if (this.cookies != null) {
            return this.cookies.getEntries();
        } else {
            return Collections.emptyList();
        }
    }

    public Map<NottableString, NottableString> getCookieMap() {
        if (this.cookies != null) {
            return this.cookies.getMap();
        } else {
            return null;
        }
    }

    public boolean cookieHeaderDoesNotAlreadyExists(Cookie cookieValue) {
        List<String> setCookieHeaders = getHeader(SET_COOKIE.toString());
        for (String setCookieHeader : setCookieHeaders) {
            String existingCookieName = ClientCookieDecoder.LAX.decode(setCookieHeader).name();
            String existingCookieValue = ClientCookieDecoder.LAX.decode(setCookieHeader).value();
            if (existingCookieName.equalsIgnoreCase(cookieValue.getName().getValue()) && existingCookieValue.equalsIgnoreCase(cookieValue.getValue().toString())) {
                return false;
            }
        }
        return true;
    }

    public boolean cookieHeaderDoesNotAlreadyExists(String name, String value) {
        List<String> setCookieHeaders = getHeader(SET_COOKIE.toString());
        for (String setCookieHeader : setCookieHeaders) {
            String existingCookieName = ClientCookieDecoder.LAX.decode(setCookieHeader).name();
            String existingCookieValue = ClientCookieDecoder.LAX.decode(setCookieHeader).value();
            if (existingCookieName.equalsIgnoreCase(name) && existingCookieValue.equalsIgnoreCase(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The connection options for override the default connection behaviour, this allows full control of headers such
     * as "Connection" or "Content-Length" or controlling whether the socket is closed after the response has been sent
     *
     * @param connectionOptions the connection options for override the default connection behaviour
     */
    public HttpResponse withConnectionOptions(ConnectionOptions connectionOptions) {
        this.connectionOptions = connectionOptions;
        this.hashCode = 0;
        return this;
    }

    public ConnectionOptions getConnectionOptions() {
        return connectionOptions;
    }

    /**
     * Attach a retry/backoff recovery primitive: serve a failure response for the first
     * {@code failTimes} matches, then serve this configured response. Lets a client's
     * retry/backoff logic be tested deterministically against a transiently-failing
     * dependency. A {@code null} {@code recoverAfter} (the default) is inert and leaves
     * this response unchanged.
     *
     * @param recoverAfter the recovery primitive, or {@code null} for no recovery behaviour
     * @see RecoverAfter
     */
    public HttpResponse withRecoverAfter(RecoverAfter recoverAfter) {
        this.recoverAfter = recoverAfter;
        this.hashCode = 0;
        return this;
    }

    public RecoverAfter getRecoverAfter() {
        return recoverAfter;
    }

    public HttpResponse withStreamId(Integer streamId) {
        this.streamId = streamId;
        this.hashCode = 0;
        return this;
    }

    public Integer getStreamId() {
        return streamId;
    }

    public HttpResponse withTiming(Timing timing) {
        this.timing = timing;
        this.hashCode = 0;
        return this;
    }

    public Timing getTiming() {
        return timing;
    }

    /**
     * Attach a streaming body to this response. When set, the response head has been
     * delivered but the body is still arriving incrementally via this sink.
     *
     * @param streamingBody the streaming body sink
     */
    public HttpResponse withStreamingBody(StreamingBody streamingBody) {
        this.streamingBody = streamingBody;
        return this;
    }

    /**
     * @return the streaming body sink, or null if this is a regular fully-buffered response
     */
    @JsonIgnore
    public StreamingBody getStreamingBody() {
        return streamingBody;
    }

    @Override
    @JsonIgnore
    public Type getType() {
        return Type.RESPONSE;
    }

    public HttpResponse shallowClone() {
        return response()
            .withStatusCode(statusCode)
            .withStatusCodeRange(statusCodeRange)
            .withReasonPhrase(reasonPhrase)
            .withBody(body)
            .withGenerateFromSchema(generateFromSchema)
            .withHeaders(headers)
            .withTrailers(trailers)
            .withCookies(cookies)
            .withDelay(getDelay())
            .withConnectionOptions(connectionOptions)
            .withRecoverAfter(recoverAfter)
            .withStreamId(streamId)
            .withTiming(timing);
    }


    @SuppressWarnings("MethodDoesntCallSuperMethod")
    public HttpResponse clone() {
        return response()
            .withStatusCode(statusCode)
            .withStatusCodeRange(statusCodeRange)
            .withReasonPhrase(reasonPhrase)
            .withBody(body)
            .withGenerateFromSchema(generateFromSchema)
            .withHeaders(headers != null ? headers.clone() : null)
            .withTrailers(trailers != null ? trailers.clone() : null)
            .withCookies(cookies != null ? cookies.clone() : null)
            .withDelay(getDelay())
            .withConnectionOptions(connectionOptions)
            .withRecoverAfter(recoverAfter)
            .withStreamId(streamId)
            .withTiming(timing)
            .withStreamingBody(streamingBody);
    }

    public HttpResponse update(HttpResponse responseOverride, HttpResponseModifier responseModifier) {
        return update(responseOverride, responseModifier, null);
    }

    public HttpResponse update(HttpResponse responseOverride, HttpResponseModifier responseModifier, HttpRequest request) {
        if (responseOverride != null) {
            if (responseOverride.getStatusCode() != null) {
                withStatusCode(responseOverride.getStatusCode());
            }
            if (responseOverride.getReasonPhrase() != null) {
                withReasonPhrase(responseOverride.getReasonPhrase());
            }
            for (Header header : responseOverride.getHeaderList()) {
                getOrCreateHeaders().replaceEntry(header);
            }
            for (Header trailer : responseOverride.getTrailerList()) {
                getOrCreateTrailers().replaceEntry(trailer);
            }
            for (Cookie cookie : responseOverride.getCookieList()) {
                withCookie(cookie);
            }
            if (responseOverride.getBody() != null) {
                withBody(responseOverride.getBody());
                // The override replaced the body, so any Content-Length inherited from the upstream
                // response now describes bytes that are no longer being sent: the client would see the
                // response truncated to the old length, or hang waiting for bytes that never arrive.
                // Drop it so the encoder recomputes it from the body actually written - unless the
                // override itself declares a Content-Length, which is honoured as a deliberate choice
                // (as is connectionOptions.contentLengthHeaderOverride, applied further downstream).
                if (isBlank(responseOverride.getFirstHeader(CONTENT_LENGTH.toString()))) {
                    removeHeader(CONTENT_LENGTH.toString());
                }
            }
            if (responseOverride.getGenerateFromSchema() != null) {
                withGenerateFromSchema(responseOverride.getGenerateFromSchema());
            }
            if (responseOverride.getConnectionOptions() != null) {
                withConnectionOptions(responseOverride.getConnectionOptions());
            }
            if (responseOverride.getStreamId() != null) {
                withStreamId(responseOverride.getStreamId());
            }
            if (responseOverride.getTiming() != null) {
                withTiming(responseOverride.getTiming());
            }
            this.hashCode = 0;
        }
        if (responseModifier != null) {
            responseModifier.applyTo(this, request);
        }
        return this;
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
        HttpResponse that = (HttpResponse) o;
        return Objects.equals(statusCode, that.statusCode) &&
            Objects.equals(statusCodeRange, that.statusCodeRange) &&
            Objects.equals(reasonPhrase, that.reasonPhrase) &&
            Objects.equals(body, that.body) &&
            Objects.equals(generateFromSchema, that.generateFromSchema) &&
            Objects.equals(headers, that.headers) &&
            Objects.equals(trailers, that.trailers) &&
            Objects.equals(cookies, that.cookies) &&
            Objects.equals(connectionOptions, that.connectionOptions) &&
            Objects.equals(recoverAfter, that.recoverAfter) &&
            Objects.equals(streamId, that.streamId);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            int computed = Objects.hash(super.hashCode(), statusCode, statusCodeRange, reasonPhrase, body, generateFromSchema, headers, trailers, cookies, connectionOptions, recoverAfter, streamId);
            hashCode = computed != 0 ? computed : 1;
        }
        return hashCode;
    }
}

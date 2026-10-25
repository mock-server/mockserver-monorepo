package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.codec.SnappyBlockOrFrameDecoder;
import org.mockserver.matchers.ClientIpMatcher;
import org.mockserver.matchers.ParsedBodyCache;
import org.mockserver.socket.SocketAddresses;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.NottableSchemaString.schemaString;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.SocketAddress.Scheme.HTTP;
import static org.mockserver.model.SocketAddress.Scheme.HTTPS;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"rawtypes", "UnusedReturnValue"})
public class HttpRequest extends RequestDefinition implements HttpMessage<HttpRequest, Body> {
    private int hashCode;
    private NottableString method = string("");
    private NottableString path = string("");
    private Parameters pathParameters;
    private Parameters queryStringParameters;
    private Body body = null;
    private byte[] originalBody = null;
    private Headers headers;
    private Cookies cookies;
    private Boolean keepAlive = null;
    private Boolean secure = null;
    private Boolean respondBeforeBody = null;
    private Protocol protocol = null;
    private Integer streamId = null;
    private List<X509Certificate> clientCertificateChain;
    private ClientCertificate clientCertificate;
    private Jwt jwt;
    private NottableString clientIp;
    private SocketAddress socketAddress;
    private String localAddress;
    // the text (a String), or, once any expectation uses clientIp, a RemoteAddress the transport shares across a
    // connection that also holds the peer's InetAddress; one slot either way, so HttpRequest gains no field
    private Object remoteAddress;
    // Per-request memoization of the expensive body conversion performed during matching
    // (e.g. the XML DOM parse + ObjectMapper serialisation in JsonSchemaBodyDecoder.convertToJson).
    // The conversion result is a pure function of the request body + the target content type, so it
    // can be computed once per request and reused across the N-expectation match scan instead of
    // being re-parsed once per candidate expectation. Excluded from equals/hashCode/clone/JSON: it
    // is a derived, transient cache, not part of request identity. The body is immutable during a
    // match scan (only path/query parameters are mutated, never the body), and a request is matched
    // on a single thread, so this lazy cache is safe without synchronisation.
    @JsonIgnore
    private transient Map<ConvertedBodyType, String> convertedBodyCache;
    // The body parsed once for the candidate scan in progress (see ParsedBodyCache): null outside a
    // scan, the owning Thread while a scan is open and nothing is parsed, then that thread's
    // ParsedBodyCache. Cleared when the scan ends, so it never outlives it — this request may be
    // retained by the event log.
    @JsonIgnore
    private transient volatile Object parsedBodyCache;
    private static final AtomicReferenceFieldUpdater<HttpRequest, Object> PARSED_BODY_CACHE =
        AtomicReferenceFieldUpdater.newUpdater(HttpRequest.class, Object.class, "parsedBodyCache");
    // Regex capture groups extracted from the matched expectation's path pattern when this request
    // matched. Populated post-match by HttpRequestPropertiesMatcher so a response/forward template can
    // reference the captured values (e.g. $request.pathGroups[1] in Velocity, {{ request.pathGroups.1 }}
    // in Mustache, request.pathGroups[1] in JavaScript). pathGroups is 1-based aligned with java.util.regex
    // group numbering — index 0 is the whole match, index 1 the first capturing group — so a template can
    // use the same indices a developer would expect from the pattern. namedPathGroups holds Java named
    // groups (?<name>...). Both are derived, transient, request-scoped state: excluded from
    // equals/hashCode/clone/JSON and never part of request identity.
    @JsonIgnore
    private transient List<String> pathGroups;
    @JsonIgnore
    private transient Map<String, String> namedPathGroups;

    // In-process-only marker for the server's own load-generation traffic. Set by
    // LoadScenarioOrchestrator on each generated request so MockServerEventLog can skip it on the
    // driver, keeping the bounded request event log free for the requests under test. It is derived,
    // transient, request-scoped state: excluded from equals/hashCode/JSON and never serialized to
    // the wire, so it cannot reach an upstream target and silently disable that target's logging.
    @JsonIgnore
    private transient boolean loadGenerated;
    // Set once the control-plane gate has seen this request: LogEntry then attaches it, and passes it as an
    // argument, only with its credential headers masked. In-process only, never part of request identity.
    @JsonIgnore
    private transient boolean controlPlaneRequest;
    // The credential values a masked copy no longer shows, kept in process only so redactSecretsInLog can still
    // scrub them from an entry's other text (a raw message or exception that quotes them). Never serialized.
    @JsonIgnore
    private transient List<String> maskedCredentialValues;
    // The body instance and Content-Encoding values a transport built this request with, so a forward can
    // tell that neither was changed and send the bytes the client sent. Identity, not equality: any
    // withBody replaces the instance. In-process only: excluded from equals/hashCode/JSON.
    @JsonIgnore
    private transient Body bodyAsReceived;
    @JsonIgnore
    private transient List<String> contentEncodingAsReceived;
    // The query string a transport could not decode; its parameters are then absent. Kept so the control plane
    // can refuse such a request rather than read it as having no parameters. In-process only, like the above.
    @JsonIgnore
    private transient String undecodableQueryString;

    /**
     * Identifies a memoizable body-conversion target. The conversion result depends only on the
     * request body and this target type, so it is safe to cache per request keyed on this value.
     */
    public enum ConvertedBodyType {
        XML_TO_JSON
    }

    /**
     * The numbered regex capture groups from the matched expectation's path pattern, 1-based aligned
     * with {@link java.util.regex.Matcher} group numbering (index 0 is the whole match). Null until a
     * match with a regex path populates it; never part of request identity.
     */
    @JsonIgnore
    public List<String> getPathGroups() {
        return pathGroups;
    }

    @JsonIgnore
    public HttpRequest withPathGroups(List<String> pathGroups) {
        this.pathGroups = pathGroups;
        return this;
    }

    /**
     * The named regex capture groups (?&lt;name&gt;...) from the matched expectation's path pattern.
     * Null until a match with a named-group regex path populates it; never part of request identity.
     */
    @JsonIgnore
    public Map<String, String> getNamedPathGroups() {
        return namedPathGroups;
    }

    /**
     * Whether this request was generated by the server's own in-process load generation. This is an
     * in-process-only marker (never serialized to the wire, so it cannot reach an upstream target)
     * used by {@link org.mockserver.log.MockServerEventLog} to keep load-generation traffic out of
     * the driver's bounded request event log; it is never part of request identity.
     */
    @JsonIgnore
    public boolean isLoadGenerated() {
        return loadGenerated;
    }

    /**
     * @return the raw query string when the transport that received this request could not decode it (so it has no
     * query string parameters), otherwise null
     */
    @JsonIgnore
    public String getUndecodableQueryString() {
        return undecodableQueryString;
    }

    public HttpRequest withUndecodableQueryString(String undecodableQueryString) {
        this.undecodableQueryString = undecodableQueryString;
        return this;
    }

    /**
     * Mark this request as a control-plane request, so every log entry it is attached to carries it with its
     * credential headers masked (see {@link org.mockserver.authentication.CredentialRedaction}).
     */
    @JsonIgnore
    public HttpRequest markControlPlaneRequest() {
        this.controlPlaneRequest = true;
        return this;
    }

    @JsonIgnore
    public boolean isControlPlaneRequest() {
        return controlPlaneRequest;
    }

    @JsonIgnore
    public List<String> getMaskedCredentialValues() {
        return maskedCredentialValues;
    }

    @JsonIgnore
    public HttpRequest withMaskedCredentialValues(List<String> maskedCredentialValues) {
        this.maskedCredentialValues = maskedCredentialValues;
        return this;
    }

    /**
     * Mark this request as the server's own in-process load-generation traffic. In-process only —
     * never serialized to the wire, so it cannot reach an upstream target.
     */
    @JsonIgnore
    public HttpRequest setLoadGenerated(boolean loadGenerated) {
        this.loadGenerated = loadGenerated;
        return this;
    }

    @JsonIgnore
    public HttpRequest withNamedPathGroups(Map<String, String> namedPathGroups) {
        this.namedPathGroups = namedPathGroups;
        return this;
    }

    public static HttpRequest request() {
        return new HttpRequest();
    }

    public static HttpRequest request(String path) {
        return new HttpRequest().withPath(path);
    }

    public static HttpRequest get(String path) {
        return new HttpRequest().withMethod("GET").withPath(path);
    }

    public static HttpRequest post(String path) {
        return new HttpRequest().withMethod("POST").withPath(path);
    }

    public static HttpRequest put(String path) {
        return new HttpRequest().withMethod("PUT").withPath(path);
    }

    public static HttpRequest delete(String path) {
        return new HttpRequest().withMethod("DELETE").withPath(path);
    }

    public static HttpRequest patch(String path) {
        return new HttpRequest().withMethod("PATCH").withPath(path);
    }

    public static HttpRequest head(String path) {
        return new HttpRequest().withMethod("HEAD").withPath(path);
    }

    public static HttpRequest options(String path) {
        return new HttpRequest().withMethod("OPTIONS").withPath(path);
    }

    public Boolean isKeepAlive() {
        return keepAlive;
    }

    /**
     * Match on whether the request was made using an HTTP persistent connection, also called HTTP keep-alive, or HTTP connection reuse
     *
     * @param isKeepAlive true if the request was made with an HTTP persistent connection
     */
    public HttpRequest withKeepAlive(Boolean isKeepAlive) {
        this.keepAlive = isKeepAlive;
        this.hashCode = 0;
        return this;
    }

    public Boolean isSecure() {
        if (socketAddress != null && socketAddress.getScheme() != null) {
            if (socketAddress.getScheme() == SocketAddress.Scheme.HTTPS) {
                secure = true;
                this.hashCode = 0;
            }
        }
        return secure;
    }

    /**
     * Match on whether the request was made over TLS or SSL (i.e. HTTPS)
     *
     * @param isSecure true if the request was made with TLS or SSL
     */
    public HttpRequest withSecure(Boolean isSecure) {
        this.secure = isSecure;
        if (socketAddress != null && socketAddress.getScheme() != null) {
            if (socketAddress.getScheme() == SocketAddress.Scheme.HTTPS) {
                secure = true;
            }
        }
        this.hashCode = 0;
        return this;
    }

    public Boolean getRespondBeforeBody() {
        return respondBeforeBody;
    }

    /**
     * Match this request without waiting for the body to be received, and send the configured response
     * before the body is consumed. Matchers with respondBeforeBody=true must not specify a body matcher
     * and must use a RESPONSE or ERROR action; combine with connectionOptions.closeSocket=true on the
     * response to close the connection after the response is sent. Useful for testing client behaviour
     * when a server responds and closes mid-upload.
     *
     * @param respondBeforeBody true to dispatch the matched response before the request body is read
     */
    public HttpRequest withRespondBeforeBody(Boolean respondBeforeBody) {
        this.respondBeforeBody = respondBeforeBody;
        this.hashCode = 0;
        return this;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    /**
     * Match on whether the request was made over HTTP or HTTP2
     *
     * @param protocol used to indicate HTTP or HTTP2
     */
    public HttpRequest withProtocol(Protocol protocol) {
        this.protocol = protocol;
        this.hashCode = 0;
        return this;
    }

    public Integer getStreamId() {
        return streamId;
    }

    /**
     * HTTP2 stream id request was received on
     *
     * @param streamId HTTP2 stream id request was received on
     */
    public HttpRequest withStreamId(Integer streamId) {
        this.streamId = streamId;
        this.hashCode = 0;
        return this;
    }

    public List<X509Certificate> getClientCertificateChain() {
        return clientCertificateChain;
    }

    public HttpRequest withClientCertificateChain(List<X509Certificate> clientCertificateChain) {
        this.clientCertificateChain = clientCertificateChain;
        this.hashCode = 0;
        return this;
    }

    /**
     * The expectation criteria for matching the mutual-TLS client-certificate chain a request was
     * received with. Matched against the leaf (client's own) certificate of
     * {@link #getClientCertificateChain()}. Null (the default) matches any request regardless of
     * whether it presented a client certificate.
     *
     * @see ClientCertificate
     */
    public ClientCertificate getClientCertificate() {
        return clientCertificate;
    }

    /**
     * Match only requests whose mutual-TLS client-certificate leaf matches the given criteria
     * (subject CN / SAN / DN, issuer CN / DN, or SHA-256 fingerprint). A request presenting no
     * client-certificate chain never matches a non-blank criterion.
     *
     * @param clientCertificate the client-certificate matching criteria
     * @see ClientCertificate
     */
    public HttpRequest withClientCertificate(ClientCertificate clientCertificate) {
        this.clientCertificate = clientCertificate;
        this.hashCode = 0;
        return this;
    }

    /**
     * The expectation criteria for matching a JSON Web Token (JWT) carried in a request header
     * (default {@code authorization}). Null (the default) matches any request. Matching decodes the
     * token's {@code header.payload} with base64url + JSON and performs <strong>no signature
     * verification</strong> — this is request matching for test routing, not authentication.
     *
     * @see Jwt
     */
    public Jwt getJwt() {
        return jwt;
    }

    /**
     * Match only requests carrying a JWT (in the header named by the criteria, default
     * {@code authorization}) whose claims / issuer / audience / algorithm match the given criteria.
     * A request with no such header, or a malformed token, never matches a non-blank criterion.
     *
     * @param jwt the JWT matching criteria
     * @see Jwt
     */
    public HttpRequest withJwt(Jwt jwt) {
        this.jwt = jwt;
        this.hashCode = 0;
        return this;
    }

    /**
     * The client IP criteria: an IP address or CIDR range, optionally negated. Null (the default) matches any
     * request.
     */
    public NottableString getClientIp() {
        return clientIp;
    }

    /**
     * Match only requests whose client address is the given IP address or falls within the given CIDR range,
     * IPv4 or IPv6, for example {@code "192.168.1.10"}, {@code "10.0.0.0/8"} or {@code "2001:db8::/32"}. Prefix
     * with {@code "!"} to match requests from any other address. The client address is the TCP peer of the
     * connection, so behind a proxy or load balancer it is the proxy's address, not the value of
     * {@code X-Forwarded-For}; only a PROXY protocol header, read when transparent proxying is enabled, replaces it.
     * That header is accepted from any client, so this is a matching criterion, not access control.
     *
     * @param clientIp an IP address or CIDR range, optionally prefixed with "!"
     * @throws IllegalArgumentException when the value is not an IP address or CIDR range
     */
    public HttpRequest withClientIp(String clientIp) {
        return withClientIp(clientIp != null ? string(clientIp) : null);
    }

    /**
     * Match only requests whose client address is (or, when negated, is not) the given IP address or within
     * the given CIDR range; see {@link #withClientIp(String)}.
     *
     * @param clientIp an IP address or CIDR range, for example {@code not("10.0.0.0/8")}
     * @throws IllegalArgumentException when the value is not an IP address or CIDR range
     */
    public HttpRequest withClientIp(NottableString clientIp) {
        if (clientIp != null) {
            ClientIpMatcher.compile(null, clientIp);
        }
        this.clientIp = clientIp;
        this.hashCode = 0;
        return this;
    }

    public SocketAddress getSocketAddress() {
        return socketAddress;
    }

    /**
     * Specify remote address if the remote address can't be derived from the host header,
     * if no value is specified the host header will be used to determine remote address
     *
     * @param socketAddress the remote address to send request to
     */
    public HttpRequest withSocketAddress(SocketAddress socketAddress) {
        this.socketAddress = socketAddress;
        if (socketAddress != null && socketAddress.getScheme() != null) {
            if (socketAddress.getScheme() == SocketAddress.Scheme.HTTPS) {
                secure = true;
            }
        }
        this.hashCode = 0;
        return this;
    }

    /**
     * Specify remote address if the remote address can't be derived from the host header,
     * if no value is specified the host header will be used to determine remote address
     *
     * @param host   the remote host or ip to send request to
     * @param port   the remote port to send request to
     * @param scheme the scheme to use for remote socket
     */
    public HttpRequest withSocketAddress(String host, Integer port, SocketAddress.Scheme scheme) {
        this.socketAddress = new SocketAddress()
            .withHost(host)
            .withPort(port)
            .withScheme(scheme);
        this.hashCode = 0;
        return this;
    }

    /**
     * Specify remote address by attempting to derive it from the host header and / or the specified port
     *
     * @param host the remote host or ip to send request to
     * @param port the remote port to send request to
     */
    public HttpRequest withSocketAddress(String host, Integer port) {
        withSocketAddress(secure, host, port);
        this.hashCode = 0;
        return this;
    }

    /**
     * Specify remote address by attempting to derive it from the host header
     */
    public HttpRequest withSocketAddressFromHostHeader() {
        withSocketAddress(secure, getFirstHeader("host"), null);
        this.hashCode = 0;
        return this;
    }

    /**
     * Specify remote address by attempting to derive it from the host header and / or the specified port
     *
     * @param isSecure true if the request was made with TLS or SSL
     * @param host     the remote host or ip to send request to
     * @param port     the remote port to send request to
     */
    public HttpRequest withSocketAddress(Boolean isSecure, String host, Integer port) {
        if (isNotBlank(host)) {
            String[] hostParts = splitHostPort(host);
            boolean secure = Boolean.TRUE.equals(isSecure);
            if (hostParts.length > 1) {
                withSocketAddress(hostParts[0], port != null ? port : Integer.valueOf(hostParts[1]), secure ? HTTPS : HTTP);
            } else if (secure) {
                withSocketAddress(host, port != null ? port : Integer.valueOf(443), HTTPS);
            } else {
                withSocketAddress(host, port != null ? port : Integer.valueOf(80), HTTP);
            }
        }
        this.hashCode = 0;
        return this;
    }

    public static String[] splitHostPort(String hostPort) {
        // bracketed-IPv6 form per RFC 3986: starts with '[' and contains a matching ']'
        if (hostPort.startsWith("[")) {
            int endOfHost = hostPort.indexOf(']');
            if (endOfHost > 0) {
                String host = hostPort.substring(1, endOfHost);
                int startOfPort = hostPort.indexOf(':', endOfHost);
                if (startOfPort > 0) {
                    return new String[] { host, hostPort.substring(startOfPort + 1) };
                } else {
                    return new String[] { host };
                }
            }
        }
        return splitOnColon(hostPort);
    }

    // Faithful replacement for hostPort.split(":") that avoids the ArrayList split allocates on the two
    // dominant host-header shapes ("host" and "host:port"). Everything else falls back to split so the
    // result is byte-for-byte identical to the original, including trailing-empty-token removal and the
    // no-delimiter case.
    private static String[] splitOnColon(String hostPort) {
        int first = hostPort.indexOf(':');
        if (first < 0) {
            return new String[]{hostPort};
        }
        // exactly one colon with a non-empty host and a non-empty port ("host:port"): the one case where
        // split's trailing-empty handling never applies, so a direct two-element array is identical.
        if (first > 0 && first < hostPort.length() - 1 && hostPort.indexOf(':', first + 1) < 0) {
            return new String[]{hostPort.substring(0, first), hostPort.substring(first + 1)};
        }
        return hostPort.split(":");
    }

    public HttpRequest withLocalAddress(String localAddress) {
        this.localAddress = localAddress;
        this.hashCode = 0;
        return this;
    }

    public String getLocalAddress() {
        return localAddress;
    }

    public HttpRequest withRemoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress;
        this.hashCode = 0;
        return this;
    }

    /**
     * Internal: sets the remote address as the transport received it, text and IP bytes together.
     */
    public HttpRequest withReceivedRemoteAddress(RemoteAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
        this.hashCode = 0;
        return this;
    }

    public String getRemoteAddress() {
        return remoteAddress instanceof RemoteAddress received ? received.text : (String) remoteAddress;
    }

    /**
     * Internal: the IP bytes of the client address this request was received from, computed on first use, or
     * null when not known. The array is shared and must not be modified. Not part of request identity or JSON.
     */
    @JsonIgnore
    public byte[] getRemoteAddressBytes() {
        return remoteAddress instanceof RemoteAddress received ? received.bytes() : null;
    }

    /**
     * Internal: records the IP bytes of the remote address, keeping its text; not part of request identity.
     */
    public HttpRequest withRemoteAddressBytes(byte[] remoteAddressBytes) {
        this.remoteAddress = new RemoteAddress(getRemoteAddress(), null, remoteAddressBytes);
        return this;
    }

    /**
     * Internal: a remote address as received, its text (for example {@code 127.0.0.1:52000}) and the peer's
     * {@link InetAddress}, whose bytes are derived on first use. The transport shares one per connection.
     */
    public static final class RemoteAddress {
        private final String text;
        private final InetAddress address;
        // derived once, on the first clientIp comparison; volatile so the array's contents are safely published
        private volatile byte[] bytes;

        public RemoteAddress(String text, InetAddress address) {
            this(text, address, null);
        }

        private RemoteAddress(String text, InetAddress address, byte[] bytes) {
            this.text = text;
            this.address = address;
            this.bytes = bytes;
        }

        byte[] bytes() {
            byte[] result = bytes;
            if (result == null && address != null) {
                result = ClientIpMatcher.normalise(address.getAddress());
                bytes = result;
            }
            return result;
        }

        // the derived bytes are not carried over, unless there is no address to derive them from
        private RemoteAddress copy() {
            byte[] current = bytes;
            return new RemoteAddress(text, address, address == null && current != null ? current.clone() : null);
        }
    }

    private static Object copyRemoteAddress(Object remoteAddress) {
        return remoteAddress instanceof RemoteAddress received ? received.copy() : remoteAddress;
    }

    private static List<X509Certificate> copyCertificateChain(List<X509Certificate> chain) {
        if (chain == null) {
            return null;
        }
        List<X509Certificate> copy = new ArrayList<>(chain.size());
        for (X509Certificate certificate : chain) {
            copy.add(certificate != null ? certificate.deepCopy() : null);
        }
        return copy;
    }

    /**
     * The HTTP method to match on such as "GET" or "POST"
     *
     * @param method the HTTP method such as "GET" or "POST"
     */
    public HttpRequest withMethod(String method) {
        return withMethod(string(method));
    }

    /**
     * The HTTP method to match on as a JSON Schema for example:
     * <pre>
     * {
     *     "type": "string",
     *     "minLength": 2,
     *     "maxLength": 3
     * }
     *
     * or
     *
     * {
     *     "type": "string",
     *     "pattern": "^P.{2,3}$"
     * }
     *
     * or
     *
     * {
     *     "type": "string",
     *     "format": "ipv4"
     * }
     * </pre>
     * <p>
     * For full details of JSON Schema see, https://json-schema.org/understanding-json-schema/reference/string.html
     *
     * @param method the HTTP method to match on as a JSON Schema
     */
    public HttpRequest withMethodSchema(String method) {
        withMethod(schemaString(method));
        return this;
    }

    /**
     * The HTTP method all method except a specific value using the "not" operator,
     * for example this allows operations such as not("GET")
     *
     * @param method the HTTP method to not match on not("GET") or not("POST")
     */
    public HttpRequest withMethod(NottableString method) {
        this.method = method;
        this.hashCode = 0;
        return this;
    }

    public NottableString getMethod() {
        return method;
    }

    public String getMethod(String defaultValue) {
        if (isBlank(method.getValue())) {
            return defaultValue;
        } else {
            return method.getValue();
        }
    }

    /**
     * The path to match on such as "/some_mocked_path" any servlet context path is ignored for matching and should not be specified here
     * regex values are also supported such as ".*_path", see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html
     * for full details of the supported regex syntax
     *
     * @param path the path such as "/some_mocked_path" or a regex
     */
    public HttpRequest withPath(String path) {
        withPath(string(path));
        return this;
    }

    /**
     * The path to not match on for example not("/some_mocked_path") with match any path not equal to "/some_mocked_path",
     * the servlet context path is ignored for matching and should not be specified hereregex values are also supported
     * such as not(".*_path"), see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html for full details
     * of the supported regex syntax
     *
     * @param path the path to not match on such as not("/some_mocked_path") or not(".*_path")
     */
    public HttpRequest withPath(NottableString path) {
        this.path = path;
        this.hashCode = 0;
        return this;
    }

    /**
     * The path to match on as a JSON Schema for example:
     * <pre>
     * {
     *     "type": "string",
     *     "minLength": 2,
     *     "maxLength": 3
     * }
     *
     * or
     *
     * {
     *     "type": "string",
     *     "pattern": "^simp.{2}$"
     * }
     *
     * or
     *
     * {
     *     "type": "string",
     *     "format": "ipv4"
     * }
     * </pre>
     * <p>
     * For full details of JSON Schema see, https://json-schema.org/understanding-json-schema/reference/string.html
     *
     * @param path the path to match on as a JSON Schema
     */
    public HttpRequest withPathSchema(String path) {
        withPath(schemaString(path));
        return this;
    }

    public NottableString getPath() {
        return path;
    }

    public boolean matches(final String method) {
        return this.method.getValue().equals(method);
    }

    public boolean matches(final String method, final String path) {
        return this.method.getValue().equals(method) && this.path.getValue().equals(path);
    }

    public boolean matches(final String method, final String path1, final String path2) {
        if (!this.method.getValue().equals(method)) {
            return false;
        }
        final String value = this.path.getValue();
        return value.equals(path1) || value.equals(path2);
    }

    public boolean matches(final String method, final String... paths) {
        if (!this.method.getValue().equals(method)) {
            return false;
        }
        final String value = this.path.getValue();
        for (String path : paths) {
            if (value.equals(path)) {
                return true;
            }
        }
        return false;
    }

    public Parameters getPathParameters() {
        return this.pathParameters;
    }

    private Parameters getOrCreatePathParameters() {
        if (this.pathParameters == null) {
            this.pathParameters = new Parameters();
            this.hashCode = 0;
        }
        return this.pathParameters;
    }

    public HttpRequest withPathParameters(Parameters parameters) {
        if (parameters == null || parameters.isEmpty()) {
            this.pathParameters = null;
        } else {
            this.pathParameters = parameters;
        }
        this.hashCode = 0;
        return this;
    }

    /**
     * The path parameter to match on as a list of Parameter objects where the values or keys of each parameter can be either a string or a regex
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param parameters the list of Parameter objects where the values or keys of each parameter can be either a string or a regex
     */
    public HttpRequest withPathParameters(List<Parameter> parameters) {
        getOrCreatePathParameters().withEntries(parameters);
        this.hashCode = 0;
        return this;
    }

    /**
     * The path parameter to match on as a varags Parameter objects where the values or keys of each parameter can be either a string or a regex
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param parameters the varags Parameter objects where the values or keys of each parameter can be either a string or a regex
     */
    public HttpRequest withPathParameters(Parameter... parameters) {
        getOrCreatePathParameters().withEntries(parameters);
        this.hashCode = 0;
        return this;
    }

    /**
     * The path parameter to match on as a Map&lt;String, List&lt;String&gt;&gt; where the values or keys of each parameter can be either a string or a regex
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param parameters the Map&lt;String, List&lt;String&gt;&gt; object where the values or keys of each parameter can be either a string or a regex
     */
    public HttpRequest withPathParameters(Map<String, List<String>> parameters) {
        getOrCreatePathParameters().withEntries(parameters);
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one path parameter to match on as a Parameter object where the parameter values list can be a list of strings or regular expressions
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param parameter the Parameter object which can have a values list of strings or regular expressions
     */
    public HttpRequest withPathParameter(Parameter parameter) {
        getOrCreatePathParameters().withEntry(parameter);
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one path parameter to match which can specified using plain strings or regular expressions
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param name   the parameter name
     * @param values the parameter values which can be a varags of strings or regular expressions
     */
    public HttpRequest withPathParameter(String name, String... values) {
        if (values.length == 0) {
            values = new String[]{".*"};
        }
        getOrCreatePathParameters().withEntry(name, values);
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one path parameter to match which the values are JSON schema i.e. "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }"
     * (for more details of the supported JSON schema see https://json-schema.org)
     *
     * @param name   the parameter name
     * @param values the parameter values which can be a varags of JSON schemas
     */
    public HttpRequest withSchemaPathParameter(String name, String... values) {
        if (values.length == 0) {
            values = new String[]{".*"};
        }
        getOrCreatePathParameters().withEntry(string(name), Arrays.stream(values).map(NottableSchemaString::schemaString).toArray(NottableString[]::new));
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one path parameter to match on or to not match on using the NottableString, each NottableString can either be a positive matching
     * value, such as string("match"), or a value to not match on, such as not("do not match"), the string values passed to the NottableString
     * can also be a plain string or a regex (for more details of the supported regex syntax
     * see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param name   the parameter name as a NottableString
     * @param values the parameter values which can be a varags of NottableStrings
     */
    public HttpRequest withPathParameter(NottableString name, NottableString... values) {
        if (values.length == 0) {
            values = new NottableString[]{string(".*")};
        }
        getOrCreatePathParameters().withEntry(name, values);
        this.hashCode = 0;
        return this;
    }

    public List<Parameter> getPathParameterList() {
        if (this.pathParameters != null) {
            return this.pathParameters.getEntries();
        } else {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unused")
    public boolean hasPathParameter(String name, String value) {
        if (this.pathParameters != null) {
            return this.pathParameters.containsEntry(name, value);
        } else {
            return false;
        }
    }

    @SuppressWarnings("unused")
    public boolean hasPathParameter(NottableString name, NottableString value) {
        if (this.pathParameters != null) {
            return this.pathParameters.containsEntry(name, value);
        } else {
            return false;
        }
    }

    public String getFirstPathParameter(String name) {
        if (this.pathParameters != null) {
            return this.pathParameters.getFirstValue(name);
        } else {
            return "";
        }
    }

    public Parameters getQueryStringParameters() {
        return this.queryStringParameters;
    }

    private Parameters getOrCreateQueryStringParameters() {
        if (this.queryStringParameters == null) {
            this.queryStringParameters = new Parameters();
            this.hashCode = 0;
        }
        return this.queryStringParameters;
    }

    public HttpRequest withQueryStringParameters(Parameters parameters) {
        if (parameters == null || parameters.isEmpty()) {
            this.queryStringParameters = null;
        } else {
            this.queryStringParameters = parameters;
        }
        this.hashCode = 0;
        return this;
    }

    /**
     * The query string parameters to match on as a list of Parameter objects where the values or keys of each parameter can be either a string or a regex
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param parameters the list of Parameter objects where the values or keys of each parameter can be either a string or a regex
     */
    public HttpRequest withQueryStringParameters(List<Parameter> parameters) {
        getOrCreateQueryStringParameters().withEntries(parameters);
        this.hashCode = 0;
        return this;
    }

    /**
     * The query string parameters to match on as a varags Parameter objects where the values or keys of each parameter can be either a string or a regex
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param parameters the varags Parameter objects where the values or keys of each parameter can be either a string or a regex
     */
    public HttpRequest withQueryStringParameters(Parameter... parameters) {
        getOrCreateQueryStringParameters().withEntries(parameters);
        this.hashCode = 0;
        return this;
    }

    /**
     * The query string parameters to match on as a Map&lt;String, List&lt;String&gt;&gt; where the values or keys of each parameter can be either a string or a regex
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param parameters the Map&lt;String, List&lt;String&gt;&gt; object where the values or keys of each parameter can be either a string or a regex
     */
    public HttpRequest withQueryStringParameters(Map<String, List<String>> parameters) {
        getOrCreateQueryStringParameters().withEntries(parameters);
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one query string parameter to match on as a Parameter object where the parameter values list can be a list of strings or regular expressions
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param parameter the Parameter object which can have a values list of strings or regular expressions
     */
    public HttpRequest withQueryStringParameter(Parameter parameter) {
        getOrCreateQueryStringParameters().withEntry(parameter);
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one query string parameter to match which the values are plain strings or regular expressions
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param name   the parameter name
     * @param values the parameter values which can be a varags of strings or regular expressions
     */
    public HttpRequest withQueryStringParameter(String name, String... values) {
        getOrCreateQueryStringParameters().withEntry(name, values);
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one query string parameter to match which the values are JSON schema i.e. "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }"
     * (for more details of the supported JSON schema see https://json-schema.org)
     *
     * @param name   the parameter name
     * @param values the parameter values which can be a varags of JSON schemas
     */
    public HttpRequest withSchemaQueryStringParameter(String name, String... values) {
        if (values.length == 0) {
            values = new String[]{".*"};
        }
        getOrCreateQueryStringParameters().withEntry(string(name), Arrays.stream(values).map(NottableSchemaString::schemaString).toArray(NottableString[]::new));
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one query string parameter to match on or to not match on using the NottableString, each NottableString can either be a positive matching
     * value, such as string("match"), or a value to not match on, such as not("do not match"), the string values passed to the NottableString
     * can also be a plain string or a regex (for more details of the supported regex syntax
     * see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param name   the parameter name as a NottableString
     * @param values the parameter values which can be a varags of NottableStrings
     */
    public HttpRequest withQueryStringParameter(NottableString name, NottableString... values) {
        if (values.length == 0) {
            values = new NottableString[]{string(".*")};
        }
        getOrCreateQueryStringParameters().withEntry(name, values);
        this.hashCode = 0;
        return this;
    }

    public List<Parameter> getQueryStringParameterList() {
        if (this.queryStringParameters != null) {
            return this.queryStringParameters.getEntries();
        } else {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unused")
    public boolean hasQueryStringParameter(String name, String value) {
        if (this.queryStringParameters != null) {
            return this.queryStringParameters.containsEntry(name, value);
        } else {
            return false;
        }
    }

    @SuppressWarnings("unused")
    public boolean hasQueryStringParameter(NottableString name, NottableString value) {
        if (this.queryStringParameters != null) {
            return this.queryStringParameters.containsEntry(name, value);
        } else {
            return false;
        }
    }

    public String getFirstQueryStringParameter(String name) {
        if (this.queryStringParameters != null) {
            return this.queryStringParameters.getFirstValue(name);
        } else {
            return "";
        }
    }

    /**
     * The exact string body to match on such as "this is an exact string body"
     *
     * @param body the body on such as "this is an exact string body"
     */
    public HttpRequest withBody(String body) {
        if (body != null) {
            this.body = new StringBody(body);
            this.hashCode = 0;
            this.convertedBodyCache = null;
        }
        return this;
    }

    /**
     * The exact string body to match on such as "this is an exact string body"
     *
     * @param body    the body on such as "this is an exact string body"
     * @param charset character set the string will be encoded in
     */
    public HttpRequest withBody(String body, Charset charset) {
        if (body != null) {
            this.body = new StringBody(body, charset);
            this.hashCode = 0;
            this.convertedBodyCache = null;
        }
        return this;
    }

    /**
     * The body to match on as binary data such as a pdf or image
     *
     * @param body a byte array
     */
    public HttpRequest withBody(byte[] body) {
        this.body = new BinaryBody(body);
        this.hashCode = 0;
        this.convertedBodyCache = null;
        return this;
    }

    /**
     * The body match rules on such as using one of the Body subclasses as follows:
     * <p>
     * exact string match:
     * - exact("this is an exact string body");
     * <p>
     * or
     * <p>
     * - new StringBody("this is an exact string body")
     * <p>
     * regular expression match:
     * - regex("username[a-z]{4}");
     * <p>
     * or
     * <p>
     * - new RegexBody("username[a-z]{4}");
     * <p>
     * json match:
     * - json("{username: 'foo', password: 'bar'}");
     * <p>
     * or
     * <p>
     * - json("{username: 'foo', password: 'bar'}", MatchType.STRICT);
     * <p>
     * or
     * <p>
     * - new JsonBody("{username: 'foo', password: 'bar'}");
     * <p>
     * json schema match:
     * - jsonSchema("{type: 'object', properties: { 'username': { 'type': 'string' }, 'password': { 'type': 'string' } }, 'required': ['username', 'password']}");
     * <p>
     * or
     * <p>
     * - jsonSchemaFromResource("org/mockserver/model/loginSchema.json");
     * <p>
     * or
     * <p>
     * - new JsonSchemaBody("{type: 'object', properties: { 'username': { 'type': 'string' }, 'password': { 'type': 'string' } }, 'required': ['username', 'password']}");
     * <p>
     * xpath match:
     * - xpath("/element[key = 'some_key' and value = 'some_value']");
     * <p>
     * or
     * <p>
     * - new XPathBody("/element[key = 'some_key' and value = 'some_value']");
     * <p>
     * body parameter match:
     * - params(
     * param("name_one", "value_one_one", "value_one_two")
     * param("name_two", "value_two")
     * );
     * <p>
     * or
     * <p>
     * - new ParameterBody(
     * new Parameter("name_one", "value_one_one", "value_one_two")
     * new Parameter("name_two", "value_two")
     * );
     * <p>
     * binary match:
     * - binary(IOUtils.readFully(getClass().getClassLoader().getResourceAsStream("example.pdf"), 1024));
     * <p>
     * or
     * <p>
     * - new BinaryBody(IOUtils.readFully(getClass().getClassLoader().getResourceAsStream("example.pdf"), 1024));
     * <p>
     * for more details of the supported regular expression syntax see <a href="http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html">http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html</a>
     * for more details of the supported json syntax see <a href="http://jsonassert.skyscreamer.org">http://jsonassert.skyscreamer.org</a>
     * for more details of the supported json schema syntax see <a href="http://json-schema.org/">http://json-schema.org/</a>
     * for more detail of XPath syntax see <a href="http://saxon.sourceforge.net/saxon6.5.3/expressions.html">http://saxon.sourceforge.net/saxon6.5.3/expressions.html</a>
     *
     * @param body an instance of one of the Body subclasses including StringBody, ParameterBody or BinaryBody
     */
    public HttpRequest withBody(Body body) {
        this.body = body;
        this.hashCode = 0;
        this.convertedBodyCache = null;
        return this;
    }

    public Body getBody() {
        return body;
    }

    @JsonIgnore
    public byte[] getBodyAsRawBytes() {
        return this.body != null ? this.body.getRawBytes() : new byte[0];
    }

    /**
     * When a request arrived with a Content-Encoding (e.g. gzip) and MockServer decompressed it, this holds the
     * original bytes exactly as received on the wire (still compressed). It is null when the request body was not
     * compressed, so {@link #getBodyAsRawBytes()} (the decompressed body) and this value together let you inspect
     * both representations. A BinaryBody expectation matches against either representation.
     */
    public byte[] getOriginalBody() {
        return originalBody;
    }

    /**
     * @return the original on-the-wire (compressed) body bytes when the request was compressed, otherwise the
     * decompressed raw bytes — i.e. always the bytes as the client sent them.
     */
    @JsonIgnore
    public byte[] getBodyAsOriginalRawBytes() {
        return this.originalBody != null ? this.originalBody : getBodyAsRawBytes();
    }

    public HttpRequest withOriginalBody(byte[] originalBody) {
        // originalBody is intentionally excluded from equals/hashCode (it is a wire-representation
        // detail, not a matching key), so the cached hashCode does not need to be reset here
        this.originalBody = originalBody;
        return this;
    }

    /**
     * Records that the current body and {@code Content-Encoding} header are exactly as received from the client,
     * with {@link #getBodyAsOriginalRawBytes()} the bytes it sent. Called by the server's transports once they have
     * built a request that arrived with a {@code Content-Encoding}; a request with no body or no {@code Content-Encoding}
     * is not marked. A later {@link #withBody} or change to that header ends it. In-process only, never serialised.
     */
    @JsonIgnore
    public HttpRequest markBodyAsReceived() {
        List<String> contentEncodings = body != null ? getHeader(CONTENT_ENCODING.toString()) : Collections.emptyList();
        this.bodyAsReceived = contentEncodings.isEmpty() ? null : body;
        this.contentEncodingAsReceived = contentEncodings.isEmpty() ? null : contentEncodings;
        return this;
    }

    /**
     * Whether the body and {@code Content-Encoding} header are still those {@link #markBodyAsReceived()} recorded, so
     * {@link #getBodyAsOriginalRawBytes()} is still a correct wire form of this request's body. Always false for a
     * request with no {@code Content-Encoding}, without looking the header up.
     */
    @JsonIgnore
    public boolean isBodyAsReceived() {
        return body != null
            && body == bodyAsReceived
            && Objects.equals(contentEncodingAsReceived, getHeader(CONTENT_ENCODING.toString()));
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
     * Returns the memoized result of an expensive body conversion for the given target type,
     * computing and caching it on first request. Subsequent calls for the same target return the
     * cached value without re-running the conversion. Used by {@code JsonSchemaBodyDecoder} so the
     * incoming request body is parsed once per request rather than once per candidate expectation
     * during the matching scan.
     * <p>
     * The supplier is invoked at most once per (request, target) pair. Whatever it produces —
     * including {@code null} or a fall-back value — is cached, so the observable behaviour of a
     * cached call is identical to recomputing it. The supplier therefore MUST itself preserve the
     * original conversion semantics (e.g. swallow/translate exceptions exactly as before).
     *
     * @param type     the conversion target identifying the cache slot
     * @param supplier computes the converted body on a cache miss
     * @return the converted body (possibly {@code null} if that is what the supplier produced)
     */
    @JsonIgnore
    public String getOrComputeConvertedBody(ConvertedBodyType type, java.util.function.Supplier<String> supplier) {
        if (convertedBodyCache == null) {
            convertedBodyCache = new EnumMap<>(ConvertedBodyType.class);
        }
        // EnumMap distinguishes an absent key from a present key whose value is null, so a cached
        // null fall-back result is returned without re-running the supplier
        if (convertedBodyCache.containsKey(type)) {
            return convertedBodyCache.get(type);
        }
        String converted = supplier.get();
        convertedBodyCache.put(type, converted);
        return converted;
    }

    /**
     * Opens a {@link ParsedBodyCache} scope owned by the calling thread for one candidate scan; the cache
     * itself is created only when a matcher first asks for it. Returns {@code false}, opening nothing,
     * when a scope is already open (an enclosing scan on this thread keeps using its own; any other
     * thread parses for itself). A {@code true} result MUST be paired with
     * {@link #closeParsedBodyCache()} in a {@code finally} block.
     * <p>Internal to request matching; not a supported client API.
     */
    @JsonIgnore
    public boolean openParsedBodyCache() {
        return PARSED_BODY_CACHE.compareAndSet(this, null, Thread.currentThread());
    }

    /**
     * Closes the scope opened by {@link #openParsedBodyCache()} on this thread, emptying its cache.
     * <p>Internal to request matching; not a supported client API.
     */
    @JsonIgnore
    public void closeParsedBodyCache() {
        Object scope = parsedBodyCache;
        if (scope instanceof ParsedBodyCache cache && cache.isOwnedByCurrentThread()) {
            PARSED_BODY_CACHE.compareAndSet(this, scope, null);
            cache.clear();
        } else if (scope == Thread.currentThread()) {
            PARSED_BODY_CACHE.compareAndSet(this, scope, null);
        }
    }

    /**
     * The cache of the scope the calling thread has open on this request, created on first use, or
     * {@code null} when this thread has no scope open.
     * <p>Internal to request matching; not a supported client API.
     */
    @JsonIgnore
    public ParsedBodyCache parsedBodyCacheForCurrentThread() {
        Object scope = parsedBodyCache;
        if (scope instanceof ParsedBodyCache cache) {
            return cache.isOwnedByCurrentThread() ? cache : null;
        }
        if (scope == Thread.currentThread()) {
            // only the owning thread replaces its own scope marker, so no other write can intervene
            ParsedBodyCache cache = ParsedBodyCache.forCurrentThread();
            parsedBodyCache = cache;
            return cache;
        }
        return null;
    }

    /**
     * The body as text: the same as {@link #getBodyAsString()}, except that a binary body received
     * with no Content-Type (bytes that are not valid UTF-8) is decoded as lenient UTF-8 rather than
     * returned as base64, as such a body always was.
     */
    @JsonIgnore
    public String getBodyAsText() {
        return BinaryBody.matchableString(body, getFirstHeader(CONTENT_TYPE.toString()));
    }

    @JsonIgnore
    public String getBodyAsJsonOrXmlString() {
        if (body != null) {
            if (body instanceof StringBody) {
                // if it should be json (and it has been validated i.e. control plane request)
                // assume the Content-Type header was forgotten so should be parsed as json
                return new String(BodyTextEncoder.encode(body.toString(), MediaType.parse(getFirstHeader(CONTENT_TYPE.toString())).getCharsetOrDefault()), StandardCharsets.UTF_8);
            } else {
                return getBodyAsText();
            }
        } else {
            return null;
        }
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

    public HttpRequest withHeaders(Headers headers) {
        if (headers == null || headers.isEmpty()) {
            this.headers = null;
        } else {
            this.headers = headers;
        }
        this.hashCode = 0;
        return this;
    }

    /**
     * The headers to match on as a list of Header objects where the values or keys of each header can be either a string or a regex
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param headers the list of Header objects where the values or keys of each header can be either a string or a regex
     */
    public HttpRequest withHeaders(List<Header> headers) {
        getOrCreateHeaders().withEntries(headers);
        this.hashCode = 0;
        return this;
    }

    /**
     * The headers to match on as a varags of Header objects where the values or keys of each header can be either a string or a regex
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param headers the varags of Header objects where the values or keys of each header can be either a string or a regex
     */
    public HttpRequest withHeaders(Header... headers) {
        getOrCreateHeaders().withEntries(headers);
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one header to match on as a Header object where the header values list can be a list of strings or regular expressions
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param header the Header object which can have a values list of strings or regular expressions
     */
    public HttpRequest withHeader(Header header) {
        getOrCreateHeaders().withEntry(header);
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one header to match which can specified using plain strings or regular expressions
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param name   the header name
     * @param values the header values which can be a varags of strings or regular expressions
     */
    public HttpRequest withHeader(String name, String... values) {
        if (values.length == 0) {
            values = new String[]{".*"};
        }
        getOrCreateHeaders().withEntry(header(name, values));
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one header to match which the values are JSON schema i.e. "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }"
     * (for more details of the supported JSON schema see https://json-schema.org)
     *
     * @param name   the header name
     * @param values the header values which can be a varags of JSON schemas
     */
    public HttpRequest withSchemaHeader(String name, String... values) {
        if (values.length == 0) {
            values = new String[]{".*"};
        }
        getOrCreateHeaders().withEntry(header(string(name), Arrays.stream(values).map(NottableSchemaString::schemaString).toArray(NottableString[]::new)));
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one header to match on or to not match on using the NottableString, each NottableString can either be a positive matching value,
     * such as string("match"), or a value to not match on, such as not("do not match"), the string values passed to the NottableString
     * can also be a plain string or a regex (for more details of the supported regex syntax
     * see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param name   the header name as a NottableString
     * @param values the header values which can be a varags of NottableStrings
     */
    public HttpRequest withHeader(NottableString name, NottableString... values) {
        if (values.length == 0) {
            values = new NottableString[]{string(".*")};
        }
        getOrCreateHeaders().withEntry(header(name, values));
        this.hashCode = 0;
        return this;
    }

    public HttpRequest withContentType(MediaType mediaType) {
        getOrCreateHeaders().withEntry(header(CONTENT_TYPE.toString(), mediaType.toString()));
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one header to match on as a Header object where the header values list can be a list of strings or regular expressions
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param header the Header object which can have a values list of strings or regular expressions
     */
    public HttpRequest replaceHeader(Header header) {
        getOrCreateHeaders().replaceEntry(header);
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

    /**
     * Returns true if a header with the specified name and value has been added
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

    public HttpRequest removeHeader(String name) {
        if (this.headers != null) {
            headers.remove(name);
            this.hashCode = 0;
        }
        return this;
    }

    public HttpRequest removeHeader(NottableString name) {
        if (this.headers != null) {
            headers.remove(name);
            this.hashCode = 0;
        }
        return this;
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

    public HttpRequest withCookies(Cookies cookies) {
        if (cookies == null || cookies.isEmpty()) {
            this.cookies = null;
        } else {
            this.cookies = cookies;
        }
        this.hashCode = 0;
        return this;
    }

    /**
     * The cookies to match on as a list of Cookie objects where the values or keys of each cookie can be either a string or a regex
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param cookies a list of Cookie objects
     */
    public HttpRequest withCookies(List<Cookie> cookies) {
        getOrCreateCookies().withEntries(cookies);
        this.hashCode = 0;
        return this;
    }

    /**
     * The cookies to match on as a varags Cookie objects where the values or keys of each cookie can be either a string or a regex
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param cookies a varargs of Cookie objects
     */
    public HttpRequest withCookies(Cookie... cookies) {
        getOrCreateCookies().withEntries(cookies);
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one cookie to match on as a Cookie object where the cookie values list can be a list of strings or regular expressions
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param cookie a Cookie object
     */
    public HttpRequest withCookie(Cookie cookie) {
        getOrCreateCookies().withEntry(cookie);
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one cookie to match on, which the value is plain strings or regular expressions
     * (for more details of the supported regex syntax see http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param name  the cookies name
     * @param value the cookies value
     */
    public HttpRequest withCookie(String name, String value) {
        getOrCreateCookies().withEntry(name, value);
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one cookie to match on, which the value the values is JSON schema i.e. "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }"
     * (for more details of the supported JSON schema see https://json-schema.org)
     *
     * @param name  the cookies name
     * @param value the cookies value as JSON schema
     */
    public HttpRequest withSchemaCookie(String name, String value) {
        getOrCreateCookies().withEntry(string(name), schemaString(value));
        this.hashCode = 0;
        return this;
    }

    /**
     * Adds one cookie to match on or to not match on using the NottableString, each NottableString can either be a positive matching value,
     * such as string("match"), or a value to not match on, such as not("do not match"), the string values passed to the NottableString
     * can be a plain string or a regex (for more details of the supported regex syntax see
     * http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)
     *
     * @param name  the cookies name
     * @param value the cookies value
     */
    public HttpRequest withCookie(NottableString name, NottableString value) {
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

    public InetSocketAddress socketAddressFromHostHeader() {
        return socketAddressFromHostHeader(true);
    }

    /**
     * As {@link #socketAddressFromHostHeader()}, but the host name is not looked up: the address is unresolved
     * unless the host is an IP literal.
     */
    public InetSocketAddress unresolvedSocketAddressFromHostHeader() {
        return socketAddressFromHostHeader(false);
    }

    private InetSocketAddress socketAddressFromHostHeader(boolean resolve) {
        if (socketAddress != null && socketAddress.getHost() != null) {
            boolean isSsl = socketAddress.getScheme() != null && socketAddress.getScheme().equals(SocketAddress.Scheme.HTTPS);
            return socketAddress(socketAddress.getHost(), socketAddress.getPort() != null ? socketAddress.getPort() : isSsl ? 443 : 80, resolve);
        } else if (isNotBlank(getFirstHeader(HOST.toString()))) {
            boolean isSsl = isSecure() != null && isSecure();
            String[] hostHeaderParts = splitHostPort(getFirstHeader(HOST.toString()));
            return socketAddress(hostHeaderParts[0], hostHeaderParts.length > 1 ? Integer.parseInt(hostHeaderParts[1]) : isSsl ? 443 : 80, resolve);
        } else {
            throw new IllegalArgumentException("Host header must be provided to determine remote socket address, the request \"" + getMethod("") + " " + getPath() + "\" does not include the \"Host\" header");
        }
    }

    private static InetSocketAddress socketAddress(String host, int port, boolean resolve) {
        return resolve ? new InetSocketAddress(host, port) : SocketAddresses.unresolvedUnlessIpLiteral(host, port);
    }

    public HttpRequest shallowClone() {
        HttpRequest clone = not(request(), not)
            .withMethod(method)
            .withPath(path)
            .withPathParameters(pathParameters)
            .withQueryStringParameters(queryStringParameters)
            .withBody(body)
            .withOriginalBody(originalBody)
            .withHeaders(headers)
            .withCookies(cookies)
            .withKeepAlive(keepAlive)
            .withSecure(secure)
            .withRespondBeforeBody(respondBeforeBody)
            .withProtocol(protocol)
            .withStreamId(streamId)
            .withClientCertificateChain(clientCertificateChain)
            .withClientCertificate(clientCertificate)
            .withJwt(jwt)
            .withSocketAddress(socketAddress)
            .withLocalAddress(localAddress);
        clone.remoteAddress = remoteAddress;
        clone.clientIp = clientIp;
        clone.withReceivedTimestamp(getReceivedTimestamp());
        clone.bodyAsReceived = bodyAsReceived;
        clone.contentEncodingAsReceived = contentEncodingAsReceived;
        clone.undecodableQueryString = undecodableQueryString;
        clone.controlPlaneRequest = controlPlaneRequest;
        return clone;
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    public HttpRequest clone() {
        HttpRequest clone = not(request(), not)
            .withMethod(method)
            .withPath(path)
            .withPathParameters(pathParameters != null ? pathParameters.clone() : null)
            .withQueryStringParameters(queryStringParameters != null ? queryStringParameters.clone() : null)
            .withBody(body)
            .withOriginalBody(originalBody)
            .withHeaders(headers != null ? headers.clone() : null)
            .withCookies(cookies != null ? cookies.clone() : null)
            .withKeepAlive(keepAlive)
            .withSecure(secure)
            .withRespondBeforeBody(respondBeforeBody)
            .withProtocol(protocol)
            .withStreamId(streamId)
            .withClientCertificateChain(clientCertificateChain != null && !clientCertificateChain.isEmpty() ? clientCertificateChain.stream().map(X509Certificate::clone).collect(Collectors.toList()) : null)
            .withClientCertificate(clientCertificate != null ? clientCertificate.clone() : null)
            .withJwt(jwt != null ? jwt.clone() : null)
            .withSocketAddress(socketAddress)
            .withLocalAddress(localAddress);
        clone.remoteAddress = remoteAddress;
        clone.clientIp = clientIp;
        clone.withReceivedTimestamp(getReceivedTimestamp());
        clone.bodyAsReceived = bodyAsReceived;
        clone.contentEncodingAsReceived = contentEncodingAsReceived;
        clone.undecodableQueryString = undecodableQueryString;
        clone.controlPlaneRequest = controlPlaneRequest;
        return clone;
    }

    /**
     * A copy that shares no mutable state with this request, for the data-plane request filter and response
     * transformer: collections, parameters, headers, cookies, the body (see {@link Body#copy()}), byte arrays and
     * nested model objects are copied, immutable values are shared. Every field is carried over, including the
     * in-process ones, except the cached hash and the transient parse and conversion caches, which are rebuilt on
     * use, so a copy of a request whose members were edited in place after a hash was cached has no stale hash.
     * The record of the body as received carries over only while it still holds, so a forward of an unchanged
     * copy still sends the bytes the client sent. Not used on a request's normal path.
     */
    public HttpRequest deepCopy() {
        HttpRequest copy = new HttpRequest();
        copy.not = not;
        copy.withLogCorrelationId(getLogCorrelationId());
        copy.withReceivedTimestamp(getReceivedTimestamp());
        copy.method = method;
        copy.path = path;
        copy.pathParameters = pathParameters != null ? pathParameters.deepCopy() : null;
        copy.queryStringParameters = queryStringParameters != null ? queryStringParameters.deepCopy() : null;
        copy.body = body != null ? body.copy() : null;
        copy.originalBody = originalBody != null ? originalBody.clone() : null;
        copy.headers = headers != null ? headers.deepCopy() : null;
        copy.cookies = cookies != null ? cookies.clone() : null;
        copy.keepAlive = keepAlive;
        copy.secure = secure;
        copy.respondBeforeBody = respondBeforeBody;
        copy.protocol = protocol;
        copy.streamId = streamId;
        copy.clientCertificateChain = copyCertificateChain(clientCertificateChain);
        copy.clientCertificate = clientCertificate != null ? clientCertificate.clone() : null;
        copy.jwt = jwt != null ? jwt.clone() : null;
        copy.clientIp = clientIp;
        copy.socketAddress = socketAddress != null ? socketAddress.deepCopy() : null;
        copy.localAddress = localAddress;
        copy.remoteAddress = copyRemoteAddress(remoteAddress);
        copy.pathGroups = pathGroups != null ? new ArrayList<>(pathGroups) : null;
        copy.namedPathGroups = namedPathGroups != null ? new LinkedHashMap<>(namedPathGroups) : null;
        copy.loadGenerated = loadGenerated;
        copy.controlPlaneRequest = controlPlaneRequest;
        copy.maskedCredentialValues = maskedCredentialValues != null ? new ArrayList<>(maskedCredentialValues) : null;
        copy.bodyAsReceived = bodyAsReceived != null && bodyAsReceived == body ? copy.body : null;
        copy.contentEncodingAsReceived = contentEncodingAsReceived != null ? new ArrayList<>(contentEncodingAsReceived) : null;
        copy.undecodableQueryString = undecodableQueryString;
        return copy;
    }

    /**
     * For the data-plane request filter: sets every field the transport owns to {@code received}'s, so a filter can
     * change none of them. Copies are taken of the mutable ones, as {@code received} may share them with the
     * connection. The socket address's host and port are not among them, so a filter may change where a request is
     * forwarded, but not whether over TLS: a socket address it changed takes the scheme of the received connection.
     */
    HttpRequest restampTransportFields(HttpRequest received) {
        streamId = received.streamId;
        keepAlive = received.keepAlive;
        secure = received.secure;
        // a filter steers host and port only: http or https follows the received connection, so a changed socket
        // address takes the received scheme, and isSecure(), which an https address would turn on, stays the client's
        if (socketAddress != null && !Objects.equals(socketAddress, received.socketAddress)) {
            boolean receivedOverTls = Boolean.TRUE.equals(received.secure)
                || received.socketAddress != null && received.socketAddress.getScheme() == SocketAddress.Scheme.HTTPS;
            socketAddress.withScheme(receivedOverTls ? SocketAddress.Scheme.HTTPS : SocketAddress.Scheme.HTTP);
        }
        protocol = received.protocol;
        clientCertificateChain = copyCertificateChain(received.clientCertificateChain);
        clientCertificate = received.clientCertificate != null ? received.clientCertificate.clone() : null;
        jwt = received.jwt != null ? received.jwt.clone() : null;
        clientIp = received.clientIp;
        remoteAddress = copyRemoteAddress(received.remoteAddress);
        localAddress = received.localAddress;
        withLogCorrelationId(received.getLogCorrelationId());
        withReceivedTimestamp(received.getReceivedTimestamp());
        respondBeforeBody = received.respondBeforeBody;
        undecodableQueryString = received.undecodableQueryString;
        loadGenerated = received.loadGenerated;
        controlPlaneRequest = received.controlPlaneRequest;
        maskedCredentialValues = received.maskedCredentialValues != null ? new ArrayList<>(received.maskedCredentialValues) : null;
        hashCode = 0;
        return this;
    }

    /**
     * For the data-plane request filter: when this request's body equals {@code input}'s by value, takes
     * {@code input}'s record of the body as received (the original body and the Content-Encoding values), so a
     * forward still sends the bytes the client sent; otherwise clears it, so a forward sends this body, encoded
     * again in its coding, as for an override that changes the body. A framed Snappy original body is kept, as an
     * override keeps it, because the forward re-frames a changed body only when the original was framed.
     */
    HttpRequest reconcileBodyProvenance(HttpRequest input) {
        boolean unchanged = Objects.equals(body, input.body);
        boolean keepOriginalBody = unchanged || SnappyBlockOrFrameDecoder.isFramed(input.originalBody);
        originalBody = keepOriginalBody && input.originalBody != null ? input.originalBody.clone() : null;
        bodyAsReceived = unchanged && input.bodyAsReceived != null && input.bodyAsReceived == input.body ? body : null;
        contentEncodingAsReceived = unchanged && input.contentEncodingAsReceived != null ? new ArrayList<>(input.contentEncodingAsReceived) : null;
        return this;
    }

    public HttpRequest update(HttpRequest requestOverride, HttpRequestModifier requestModifier) {
        if (requestOverride != null) {
            if (requestOverride.getMethod() != null && isNotBlank(requestOverride.getMethod().getValue())) {
                withMethod(requestOverride.getMethod());
            }
            if (requestOverride.getPath() != null && isNotBlank(requestOverride.getPath().getValue())) {
                withPath(requestOverride.getPath());
            }
            for (Parameter parameter : requestOverride.getPathParameterList()) {
                getOrCreatePathParameters().replaceEntry(parameter);
            }
            for (Parameter parameter : requestOverride.getQueryStringParameterList()) {
                getOrCreateQueryStringParameters().replaceEntry(parameter);
            }
            if (requestOverride.getBody() != null) {
                withBody(requestOverride.getBody());
            }
            for (Header header : requestOverride.getHeaderList()) {
                getOrCreateHeaders().replaceEntry(header);
            }
            for (Cookie cookie : requestOverride.getCookieList()) {
                withCookie(cookie);
            }
            if (requestOverride.isSecure() != null) {
                withSecure(requestOverride.isSecure());
            }
            if (requestOverride.getProtocol() != null) {
                withProtocol(requestOverride.getProtocol());
            }
            if (requestOverride.getStreamId() != null) {
                withStreamId(requestOverride.getStreamId());
            }
            if (requestOverride.isKeepAlive() != null) {
                withKeepAlive(requestOverride.isKeepAlive());
            }
            if (requestOverride.getRespondBeforeBody() != null) {
                withRespondBeforeBody(requestOverride.getRespondBeforeBody());
            }
            if (requestOverride.getSocketAddress() != null) {
                withSocketAddress(requestOverride.getSocketAddress());
            }
            this.hashCode = 0;
        }
        if (requestModifier != null) {
            if (requestModifier.getPath() != null) {
                withPath(requestModifier.getPath().update(getPath()));
            }
            if (requestModifier.getQueryStringParameters() != null) {
                withQueryStringParameters(requestModifier.getQueryStringParameters().update(getQueryStringParameters()));
            }
            if (requestModifier.getHeaders() != null) {
                withHeaders(requestModifier.getHeaders().update(getHeaders()));
            }
            if (requestModifier.getCookies() != null) {
                withCookies(requestModifier.getCookies().update(getCookies()));
            }
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
        HttpRequest that = (HttpRequest) o;
        return Objects.equals(method, that.method) &&
            Objects.equals(path, that.path) &&
            Objects.equals(pathParameters, that.pathParameters) &&
            Objects.equals(queryStringParameters, that.queryStringParameters) &&
            Objects.equals(body, that.body) &&
            Objects.equals(headers, that.headers) &&
            Objects.equals(cookies, that.cookies) &&
            Objects.equals(keepAlive, that.keepAlive) &&
            Objects.equals(secure, that.secure) &&
            Objects.equals(respondBeforeBody, that.respondBeforeBody) &&
            Objects.equals(protocol, that.protocol) &&
            Objects.equals(streamId, that.streamId) &&
            Objects.equals(clientCertificateChain, that.clientCertificateChain) &&
            Objects.equals(clientCertificate, that.clientCertificate) &&
            Objects.equals(jwt, that.jwt) &&
            Objects.equals(clientIp, that.clientIp) &&
            Objects.equals(socketAddress, that.socketAddress) &&
            Objects.equals(localAddress, that.localAddress) &&
            Objects.equals(getRemoteAddress(), that.getRemoteAddress());
    }

    @Override
    public int hashCode() {
        // need to call isSecure because getter can change the hashcode
        isSecure();
        if (hashCode == 0) {
            int computed = Objects.hash(super.hashCode(), method, path, pathParameters, queryStringParameters, body, headers, cookies, keepAlive, secure, respondBeforeBody, protocol, streamId, clientCertificateChain, clientCertificate, jwt, socketAddress, localAddress, getRemoteAddress());
            // mixed in separately so a request without one hashes, and allocates, exactly as before
            if (clientIp != null) {
                computed = 31 * computed + clientIp.hashCode();
            }
            hashCode = computed != 0 ? computed : 1;
        }
        return hashCode;
    }
}

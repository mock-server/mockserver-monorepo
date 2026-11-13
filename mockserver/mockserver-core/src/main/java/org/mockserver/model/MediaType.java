package org.mockserver.model;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.ObjectMapperFactory;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.*;
import static org.slf4j.event.Level.DEBUG;
import static org.slf4j.event.Level.WARN;

@SuppressWarnings("unused")
public class MediaType extends ObjectWithJsonToString {

    /**
     * The default character set for an HTTP message, if none is specified in the Content-Type header. From the HTTP 1.1 specification
     * section 3.7.1 (http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.7.1):
     * <pre>
     *     The "charset" parameter is used with some media types to define the character set (section 3.4) of the data.
     *     When no explicit charset parameter is provided by the sender, media subtypes of the "text" type are defined to
     *     have a default charset value of "ISO-8859-1" when received via HTTP. Data in character sets other than
     *     "ISO-8859-1" or its subsets MUST be labeled with an appropriate charset value.
     * </pre>
     */
    public static final Charset DEFAULT_TEXT_HTTP_CHARACTER_SET = StandardCharsets.ISO_8859_1;
    /**
     * JSON text exchanged between systems that are not part of a closed ecosystem MUST be encoded using UTF-8 [RFC3629].
     * (https://datatracker.ietf.org/doc/html/rfc8259#section-8.1)
     */
    public static final Charset DEFAULT_JSON_HTTP_CHARACTER_SET = StandardCharsets.UTF_8;
    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(ObjectMapperFactory.class);
    private static final char TYPE_SEPARATOR = '/';
    private static final char PARAMETER_START = ';';
    private final String type;
    private final String subtype;
    private final Map<String, String> parameters;
    private final Charset charset;
    private final String toString;
    private final boolean isBlank;

    private static final String CHARSET_PARAMETER = "charset";
    private static final String MEDIA_TYPE_WILDCARD = "*";
    public final static MediaType WILDCARD = new MediaType(MEDIA_TYPE_WILDCARD, MEDIA_TYPE_WILDCARD);
    public final static MediaType APPLICATION_ATOM_XML = new MediaType("application", "atom+xml");
    public final static MediaType APPLICATION_XHTML_XML = new MediaType("application", "xhtml+xml");
    public final static MediaType APPLICATION_SVG_XML = new MediaType("application", "svg+xml");
    public final static MediaType APPLICATION_XML = new MediaType("application", "xml");
    public final static MediaType APPLICATION_XML_UTF_8 = new MediaType("application", "xml", "utf-8", null);
    public final static MediaType APPLICATION_JSON = new MediaType("application", "json");
    public final static MediaType APPLICATION_JSON_UTF_8 = new MediaType("application", "json", "utf-8", null);
    public final static MediaType JSON_UTF_8 = APPLICATION_JSON_UTF_8;
    public final static MediaType APPLICATION_FORM_URLENCODED = new MediaType("application", "x-www-form-urlencoded");
    public final static MediaType FORM_DATA = new MediaType("application", "x-www-form-urlencoded");
    public final static MediaType MULTIPART_FORM_DATA = new MediaType("multipart", "form-data");
    public final static MediaType APPLICATION_OCTET_STREAM = new MediaType("application", "octet-stream");
    public static final MediaType APPLICATION_BINARY = new MediaType("application", "binary");
    public static final MediaType PDF = new MediaType("application", "pdf");
    public static final MediaType ATOM_UTF_8 = new MediaType("application", "atom+xml", "utf-8", null);
    public final static MediaType TEXT_PLAIN = new MediaType("text", "plain");
    public final static MediaType PLAIN_TEXT_UTF_8 = new MediaType("text", "plain", "utf-8", null);
    public final static MediaType TEXT_XML = new MediaType("text", "xml");
    public final static MediaType TEXT_XML_UTF_8 = new MediaType("text", "xml", "utf-8", null);
    public final static MediaType XML_UTF_8 = TEXT_XML_UTF_8;
    public final static MediaType TEXT_HTML = new MediaType("text", "html");
    public final static MediaType TEXT_HTML_UTF_8 = new MediaType("text", "html", "utf-8", null);
    public final static MediaType HTML_UTF_8 = TEXT_HTML_UTF_8;
    public static final MediaType SERVER_SENT_EVENTS = new MediaType("text", "event-stream");
    public static final MediaType APPLICATION_JSON_PATCH_JSON = new MediaType("application", "json-patch+json");
    public static final MediaType ANY_VIDEO_TYPE = new MediaType("video", MEDIA_TYPE_WILDCARD);
    public static final MediaType ANY_AUDIO_TYPE = new MediaType("audio", MEDIA_TYPE_WILDCARD);
    public static final MediaType ANY_IMAGE_TYPE = new MediaType("image", MEDIA_TYPE_WILDCARD);
    public static final MediaType QUICKTIME = new MediaType("video", "quicktime");
    public static final MediaType JPEG = new MediaType("image", "jpeg");
    public static final MediaType PNG = new MediaType("image", "png");

    /**
     * Bounded cache of parsed Content-Type headers, keyed on the verbatim header string. Every
     * request-with-body parses its Content-Type here (see {@code BodyDecoderEncoder}), and in practice
     * that header is drawn from a tiny fixed set ({@code application/json}, {@code text/plain}, ...), so
     * the same string is re-parsed - and its substrings, parameter map, backing TreeMap and toString
     * re-allocated - on every request. Caching the parsed result removes all of that on the hit path.
     * <p>
     * The value is safe to share across requests because a {@link MediaType} is immutable: its
     * {@code parameters} map is wrapped {@link Collections#unmodifiableMap unmodifiable} in the
     * constructor, so a caller that reaches it via {@link #getParameters()} cannot mutate a cached
     * (shared) instance. The key is the header EXACTLY as received, so charset-case variants
     * ({@code charset=utf-8} vs {@code charset=UTF-8}) are distinct keys mapping to independently-correct
     * results - never conflated - at the cost of at most a couple of extra entries.
     * <p>
     * The Content-Type header is attacker-controlled off the wire, so the cache is HARD-BOUNDED at
     * {@link #MAX_CACHE_ENTRIES}: once full it stops accepting new keys (a miss simply parses without
     * caching), so it can never grow without limit and, at worst, degrades to the un-cached baseline.
     */
    private static final int MAX_CACHE_ENTRIES = 1024;
    private static final Map<String, MediaType> PARSE_CACHE = new ConcurrentHashMap<>();

    public static MediaType parse(String mediaTypeHeader) {
        // null cannot be a ConcurrentHashMap key; the null/blank path is cheap and rare, so parse it directly.
        if (mediaTypeHeader == null) {
            return doParse(null);
        }
        MediaType cached = PARSE_CACHE.get(mediaTypeHeader);
        if (cached != null) {
            return cached;
        }
        MediaType parsed = doParse(mediaTypeHeader);
        // size() is a racy read across threads, but only as a soft cap on cache growth - the invariant
        // that matters (never unbounded) holds because no key is added once at/over the limit.
        if (PARSE_CACHE.size() < MAX_CACHE_ENTRIES) {
            PARSE_CACHE.putIfAbsent(mediaTypeHeader, parsed);
        }
        return parsed;
    }

    private static MediaType doParse(String mediaTypeHeader) {
        if (isNotBlank(mediaTypeHeader)) {
            int typeSeparator = mediaTypeHeader.indexOf(TYPE_SEPARATOR);
            int typeEndIndex = 0;
            String type = null;
            String subType = null;
            if (typeSeparator != -1) {
                typeEndIndex = mediaTypeHeader.indexOf(PARAMETER_START);
                if (typeEndIndex == -1) {
                    typeEndIndex = mediaTypeHeader.length();
                }
                String typeString = mediaTypeHeader.substring(0, typeEndIndex).trim();
                type = substringBefore(typeString, "/").trim().toLowerCase();
                subType = substringAfter(typeString, "/").trim().toLowerCase();
                if (typeEndIndex < mediaTypeHeader.length()) {
                    typeEndIndex++;
                }
            }
            String parameters = mediaTypeHeader.substring(typeEndIndex).trim().toLowerCase().replaceAll("\"", "");
            Map<String, String> parameterMap = new ConcurrentHashMap<>();
            if (isNotBlank(parameters)) {
                try {
                    for (String parameter : Splitter.on(';').split(parameters)) {
                        String parameterTrimmed = parameter.trim();
                        String key = substringBefore(parameterTrimmed, "=").trim();
                        String value = substringAfter(parameterTrimmed, "=").trim();
                        if (isNotBlank(key) && isNotBlank(value)) {
                            parameterMap.put(
                                key,
                                value
                            );
                        }
                    }
                    if (parameterMap.size() > 1) {
                        // sort if multiple entries to ensure equals and hashcode is consistent
                        parameterMap = parameterMap.entrySet()
                            .stream()
                            .sorted(Map.Entry.comparingByKey())
                            .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (oldValue, newValue) -> oldValue, LinkedHashMap::new
                            ));
                    }
                } catch (Throwable throwable) {
                    if (MOCK_SERVER_LOGGER.isEnabledForInstance(WARN)) {
                        MOCK_SERVER_LOGGER.logEvent(
                            new LogEntry()
                                .setLogLevel(WARN)
                                .setMessageFormat("invalid parameters format \"" + parameters + "\", expected{}see:{}")
                                .setArguments("Content-Type := type \"/\" subtype *[\";\" parameter]\nparameter := attribute \"=\" value", "https://www.w3.org/Protocols/rfc1341/4_Content-Type.html")
                                .setThrowable(throwable)
                        );
                    }
                }
            }
            return new MediaType(type, subType, parameterMap);
        } else {
            return new MediaType(null, null);
        }
    }

    private static TreeMap<String, String> createParametersMap(Map<String, String> initialValues) {
        final TreeMap<String, String> map = new TreeMap<>(String::compareToIgnoreCase);
        if (initialValues != null) {
            for (Map.Entry<String, String> entry : initialValues.entrySet()) {
                map.put(entry.getKey().toLowerCase().trim(), entry.getValue().trim());
            }
        }
        return map;
    }

    public MediaType(String type, String subtype) {
        this(type, subtype, null, null);
    }

    public MediaType(String type, String subtype, Map<String, String> parameters) {
        this(type, subtype, null, parameters);
    }

    private MediaType(String type, String subtype, String charset, Map<String, String> parameterMap) {
        this.type = isBlank(type) ? null : type;
        this.subtype = isBlank(subtype) ? null : subtype;
        // Build into a local map, then publish it wrapped unmodifiable to the final field: a parsed
        // MediaType is cached and shared across requests (see PARSE_CACHE), and getParameters() exposes
        // this map by reference, so an unmodifiable view is what stops a caller mutating a shared
        // instance - a cross-request data leak. The backing TreeMap is case-insensitive so equals/hashCode
        // (reflective, content-based) stay consistent regardless of parameter-name case.
        TreeMap<String, String> params = new TreeMap<>(String::compareToIgnoreCase);
        if (parameterMap != null) {
            parameterMap.forEach((key, value) -> params.put(key.toLowerCase(), value));
        }
        Charset parsedCharset = null;
        if (isNotBlank(charset)) {
            params.put(CHARSET_PARAMETER, charset);
            try {
                parsedCharset = Charset.forName(charset);
            } catch (Throwable throwable) {
                if (MockServerLogger.isEnabled(DEBUG)) {
                    MOCK_SERVER_LOGGER.logEvent(
                        new LogEntry()
                            .setLogLevel(DEBUG)
                            .setMessageFormat("ignoring unsupported charset with value \"" + charset + "\"")
                            .setThrowable(throwable)
                    );
                }
            }
        } else {
            try {
                if (params.containsKey(CHARSET_PARAMETER)) {
                    parsedCharset = Charset.forName(params.get(CHARSET_PARAMETER));
                }
            } catch (Throwable throwable) {
                if (MockServerLogger.isEnabled(DEBUG)) {
                    MOCK_SERVER_LOGGER.logEvent(
                        new LogEntry()
                            .setLogLevel(DEBUG)
                            .setMessageFormat("ignoring unsupported charset with value \"" + charset + "\"")
                            .setThrowable(throwable)
                    );
                }
            }
        }
        this.parameters = Collections.unmodifiableMap(params);
        this.charset = parsedCharset;
        this.toString = initialiseToString();
        this.isBlank = isBlank(this.toString);
    }

    private String initialiseToString() {
        StringBuilder stringBuilder = new StringBuilder();
        if (type != null && subtype != null) {
            stringBuilder.append(type).append(TYPE_SEPARATOR).append(subtype);
        }
        if (!parameters.isEmpty()) {
            if (stringBuilder.length() > 0) {
                stringBuilder.append(PARAMETER_START).append(' ');
            }
            stringBuilder.append(Joiner.on("; ").withKeyValueSeparator("=").join(parameters));
        }
        return stringBuilder.toString();
    }

    public static MediaType create(String type, String subType) {
        return new MediaType(type, subType);
    }

    public String getType() {
        return this.type;
    }

    public String getSubtype() {
        return this.subtype;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public MediaType withCharset(Charset charset) {
        return withCharset(charset.name());
    }

    public MediaType withCharset(String charset) {
        return new MediaType(this.type, this.subtype, charset.toLowerCase(), this.parameters);
    }

    public Charset getCharset() {
        return charset;
    }

    public Charset getCharsetOrDefault() {
        if (charset != null) {
            return charset;
        } else if (isBlank || isJson() || isXml() || isUtf8ByDefinition()) {
            return DEFAULT_JSON_HTTP_CHARACTER_SET;
        } else if (isString()) {
            return DEFAULT_TEXT_HTTP_CHARACTER_SET;
        } else {
            return null;
        }
    }

    public boolean isCompatible(MediaType other) {
        // return false if other is null, else
        return other != null &&
            // both are wildcard types, or
            (type == null || other.type == null ||
                type.equals(MEDIA_TYPE_WILDCARD) || other.type.equals(MEDIA_TYPE_WILDCARD) ||
                // same types, wildcard sub-types, or
                (type.equalsIgnoreCase(other.type) && (subtype == null || other.subtype == null)) ||
                (type.equalsIgnoreCase(other.type) && (subtype.equals(MEDIA_TYPE_WILDCARD) || other.subtype.equals(MEDIA_TYPE_WILDCARD))) ||
                // same types & sub-types
                (type.equalsIgnoreCase(other.type) && this.subtype.equalsIgnoreCase(other.subtype)));
    }

    public boolean isJson() {
        return !isBlank && contentTypeContains(new String[]{
            "json"
        }) && !contentTypeContains(new String[]{
            "ndjson"
        });
    }

    /**
     * Server-Sent Events (WHATWG HTML 9.2) and newline-delimited JSON are always UTF-8, whatever charset they omit,
     * so they must not fall back to the ISO-8859-1 default of other text.
     */
    private boolean isUtf8ByDefinition() {
        return "event-stream".equalsIgnoreCase(subtype) || "x-ndjson".equalsIgnoreCase(subtype);
    }

    public boolean isXml() {
        return !isBlank && contentTypeContains(new String[]{
            "xml"
        });
    }

    private static final String[] KNOWN_BINARY_SUBTYPES = new String[]{
        "octet-stream", "binary", "pdf", "zip", "gzip", "tar",
        "protobuf", "grpc", "thrift", "avro", "msgpack",
        "pkcs", "pkix", "x509", "woff", "woff2"
    };

    public boolean isString() {
        if (isBlank) {
            return true;
        }
        if (type == null && charset != null) {
            return true;
        }
        if (contentTypeContains(new String[]{
            "text",
            "json",
            "css",
            "html",
            "xhtml",
            "form",
            "javascript",
            "ecmascript",
            "xml",
            "wsdl",
            "csv",
            "urlencoded",
            // application/yaml (RFC 9512), application/x-yaml and application/graphql are UTF-8 text
            // formats with no "text" type and no "+json"/"+xml" suffix to fall back on. Without them
            // here the body is stored as a BinaryBody, so getBodyAsString() hands back base64 - which
            // silently corrupts every YAML spec and GraphQL SDL document sent with its natural
            // content type, on the control plane and in user request matchers alike.
            "yaml",
            "graphql"
        })) {
            return true;
        }
        if (charset != null && !isBinarySubtype()) {
            return true;
        }
        return false;
    }

    private boolean isBinarySubtype() {
        if ("image".equalsIgnoreCase(type) || "audio".equalsIgnoreCase(type) || "video".equalsIgnoreCase(type)) {
            return true;
        }
        if (subtype == null) {
            return false;
        }
        String lower = subtype.toLowerCase();
        for (String binary : KNOWN_BINARY_SUBTYPES) {
            if (lower.contains(binary)) {
                return true;
            }
        }
        return false;
    }

    private boolean contentTypeContains(String[] subStrings) {
        if (type == null || subtype == null) {
            return false;
        }
        String typeAndSubtype = (type + "/" + subtype).toLowerCase();
        for (String subString : subStrings) {
            if (typeAndSubtype.contains(subString)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return toString;
    }
}

package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.matchers.MatchType;
import org.mockserver.serialization.ObjectMapperFactory;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * @author jamesdbloom
 */
public class JsonBody extends BodyWithContentType<String> {
    private int hashCode;
    public static final MatchType DEFAULT_MATCH_TYPE = MatchType.ONLY_MATCHING_FIELDS;
    // setting default to UTF8 as per https://tools.ietf.org/html/rfc8259#section-8.1
    public static final MediaType DEFAULT_JSON_CONTENT_TYPE = MediaType.APPLICATION_JSON_UTF_8;
    // The raw bytes are canonical; json is the derived String view - cached on first read and released
    // once the entry is retained (see releaseDerivedForms). Its read/write race with the release is
    // benign: a reader either sees the cached String or null, and null re-derives the identical String.
    private transient String json;
    private final MatchType matchType;
    private final boolean matchNumbersAsStrings;
    private final byte[] rawBytes;
    private static ObjectMapper objectMapper;
    private transient JsonNode jsonNode;

    public JsonBody(String json) {
        this(json, null, DEFAULT_JSON_CONTENT_TYPE, DEFAULT_MATCH_TYPE, false);
    }

    public JsonBody(String json, MatchType matchType) {
        this(json, null, DEFAULT_JSON_CONTENT_TYPE, matchType, false);
    }

    public JsonBody(String json, Charset charset, MatchType matchType) {
        this(json, null, (charset != null ? DEFAULT_JSON_CONTENT_TYPE.withCharset(charset) : null), matchType, false);
    }

    public JsonBody(String json, byte[] rawBytes, MediaType contentType, MatchType matchType) {
        this(json, rawBytes, contentType, matchType, false);
    }

    public JsonBody(String json, byte[] rawBytes, MediaType contentType, MatchType matchType, boolean matchNumbersAsStrings) {
        super(Type.JSON, contentType);
        this.json = json;
        this.matchType = matchType;
        this.matchNumbersAsStrings = matchNumbersAsStrings;

        if (rawBytes == null && json != null) {
            this.rawBytes = encodeToRawBytes(json);
        } else {
            this.rawBytes = rawBytes;
        }
    }

    @Override
    public JsonBody copy() {
        String cached = cachedTextForCopy(json, rawBytes);
        JsonBody copy = new JsonBody(cached, rawBytes != null ? rawBytes.clone() : null, contentType, matchType, matchNumbersAsStrings);
        return withFlagsCopiedTo(copy);
    }

    public static JsonBody json(String json) {
        return new JsonBody(json);
    }

    public static JsonBody json(String json, MatchType matchType) {
        return new JsonBody(json, matchType);
    }

    public static JsonBody json(String json, MatchType matchType, boolean matchNumbersAsStrings) {
        return new JsonBody(json, null, DEFAULT_JSON_CONTENT_TYPE, matchType, matchNumbersAsStrings);
    }

    public static JsonBody json(String json, Charset charset) {
        return new JsonBody(json, null, (charset != null ? DEFAULT_JSON_CONTENT_TYPE.withCharset(charset) : null), DEFAULT_MATCH_TYPE);
    }

    public static JsonBody json(String json, Charset charset, MatchType matchType) {
        return new JsonBody(json, null, (charset != null ? DEFAULT_JSON_CONTENT_TYPE.withCharset(charset) : null), matchType);
    }

    public static JsonBody json(String json, MediaType contentType) {
        return new JsonBody(json, null, contentType, DEFAULT_MATCH_TYPE);
    }

    public static JsonBody json(String json, MediaType contentType, MatchType matchType) {
        return new JsonBody(json, null, contentType, matchType);
    }

    private static String toJson(Object object) {
        String json;
        try {
            json = ObjectMapperFactory.createObjectMapper().writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("error mapping object for json body to JSON", e);
        }
        return json;
    }

    public static JsonBody json(Object object) {
        return new JsonBody(toJson(object));
    }

    public static JsonBody json(Object object, MatchType matchType) {
        return new JsonBody(toJson(object), matchType);
    }

    public static JsonBody json(Object object, Charset charset) {
        return new JsonBody(toJson(object), null, (charset != null ? DEFAULT_JSON_CONTENT_TYPE.withCharset(charset) : null), DEFAULT_MATCH_TYPE);
    }

    public static JsonBody json(Object object, Charset charset, MatchType matchType) {
        return new JsonBody(toJson(object), null, (charset != null ? DEFAULT_JSON_CONTENT_TYPE.withCharset(charset) : null), matchType);
    }

    public static JsonBody json(Object object, MediaType contentType) {
        return new JsonBody(toJson(object), null, contentType, DEFAULT_MATCH_TYPE);
    }

    public static JsonBody json(Object object, MediaType contentType, MatchType matchType) {
        return new JsonBody(toJson(object), null, contentType, matchType);
    }

    public JsonNode get(String field) {
        // Read the cache into a local: releaseDerivedForms() can null it concurrently, and
        // re-reading the field after building would then dereference null.
        JsonNode node = jsonNode;
        if (node == null) {
            if (objectMapper == null) {
                objectMapper = ObjectMapperFactory.createObjectMapper();
            }
            try {
                node = objectMapper.readTree(getValue());
                jsonNode = node;
            } catch (JsonProcessingException jpe) {
                throw new RuntimeException(jpe.getMessage(), jpe);
            }
        }
        return node.get(field);
    }

    public String getValue() {
        String value = json;
        if (value == null && rawBytes != null) {
            value = decodeRawBytes(rawBytes);
            json = value;
        }
        return value;
    }

    @Override
    public String getValueWithoutCaching() {
        String value = json;
        return value != null ? value : decodeRawBytes(rawBytes);
    }

    @Override
    public String toStringWithoutCaching() {
        return getValueWithoutCaching();
    }

    @Override
    public void releaseDerivedForms() {
        String value = json;
        if (value != null && rawBytes != null && value.equals(decodeRawBytes(rawBytes))) {
            json = null;
        }
        jsonNode = null;
    }

    @Override
    public long retainedDerivedFormBytes() {
        String value = json;
        return value == null ? 0L : (long) value.length() * 2;
    }

    @JsonIgnore
    public byte[] getRawBytes() {
        return rawBytes;
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public boolean isMatchNumbersAsStrings() {
        return matchNumbersAsStrings;
    }

    @Override
    public String toString() {
        return getValue();
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
        JsonBody jsonBody = (JsonBody) o;
        return Objects.equals(getValue(), jsonBody.getValue()) &&
            matchType == jsonBody.matchType &&
            matchNumbersAsStrings == jsonBody.matchNumbersAsStrings &&
            Arrays.equals(rawBytes, jsonBody.rawBytes);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            // keyed on the canonical rawBytes, not the releasable json view, so the hash is stable
            // across a release and never re-materialises the String
            int result = Objects.hash(super.hashCode(), matchType, matchNumbersAsStrings);
            hashCode = 31 * result + Arrays.hashCode(rawBytes);
        }
        return hashCode;
    }
}

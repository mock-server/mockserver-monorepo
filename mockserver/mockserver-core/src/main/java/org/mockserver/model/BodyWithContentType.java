package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

import static org.mockserver.model.MediaType.DEFAULT_TEXT_HTTP_CHARACTER_SET;

/**
 * @author jamesdbloom
 */
public abstract class BodyWithContentType<T> extends Body<T> {
    private int hashCode;
    protected final MediaType contentType;

    public BodyWithContentType(Type type, MediaType contentType) {
        super(type);
        this.contentType = contentType;
    }

    @Override
    public BodyWithContentType<T> copy() {
        throw copyNotImplemented();
    }

    /**
     * For {@link #copy()}: the cached text when the bytes alone would not give it back (a blank text kept as "",
     * or text the charset cannot encode), otherwise null so the copy decodes it afresh. A cached text the bytes no
     * longer encode, because they were edited in place, is dropped with the other caches.
     */
    String cachedTextForCopy(String cached, byte[] rawBytes) {
        if (cached == null || rawBytes == null) {
            return cached;
        }
        String decoded = decodeRawBytes(rawBytes);
        if (cached.equals(decoded)) {
            return null;
        }
        boolean underivable = cached.isEmpty() ? StringUtils.isBlank(decoded) : Arrays.equals(encodeToRawBytes(cached), rawBytes);
        return underivable ? cached : null;
    }

    @JsonIgnore
    Charset determineCharacterSet(MediaType mediaType, Charset defaultCharset) {
        if (mediaType != null) {
            Charset charset = mediaType.getCharset();
            if (charset != null) {
                return charset;
            }
        }
        return defaultCharset;
    }

    @Override
    @JsonIgnore
    public Charset getCharset(Charset defaultIfNotSet) {
        return determineCharacterSet(contentType, defaultIfNotSet);
    }

    /**
     * Decode raw body bytes to the String view, resolving the charset from this body's content type
     * (falling back to the default text charset). The single place the {@code byte[] -> String}
     * conversion lives, shared by the text bodies for both lazy derivation and the release check.
     */
    @JsonIgnore
    String decodeRawBytes(byte[] rawBytes) {
        if (rawBytes == null) {
            return null;
        }
        return new String(rawBytes, determineCharacterSet(contentType, DEFAULT_TEXT_HTTP_CHARACTER_SET));
    }

    /**
     * Encode the String view to raw body bytes, in the charset {@link #decodeRawBytes} reads them back
     * in. The single place the text bodies' {@code String -> byte[]} conversion lives.
     */
    @JsonIgnore
    byte[] encodeToRawBytes(String value) {
        return BodyTextEncoder.encode(value, determineCharacterSet(contentType, DEFAULT_TEXT_HTTP_CHARACTER_SET));
    }

    @Override
    public String getContentType() {
        return (contentType != null ? contentType.toString() : null);
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
        BodyWithContentType<?> that = (BodyWithContentType<?>) o;
        return Objects.equals(contentType, that.contentType);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), contentType);
        }
        return hashCode;
    }
}

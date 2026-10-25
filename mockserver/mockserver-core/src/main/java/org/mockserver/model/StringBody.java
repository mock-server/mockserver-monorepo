package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * @author jamesdbloom
 */
public class StringBody extends BodyWithContentType<String> {
    private int hashCode;
    public static final MediaType DEFAULT_CONTENT_TYPE = MediaType.create("text", "plain");
    private final boolean subString;
    // The raw bytes are canonical; value is the derived String view - cached on first read and released
    // once the entry is retained (see releaseDerivedForms). The read/write race with the release is
    // benign: a reader either sees the cached String or null, and null re-derives the identical String.
    private transient String value;
    private final byte[] rawBytes;
    // set instead of rawBytes for a body built from bytes held in segments, which are never joined to be written
    private final SegmentedBytes segmentedBytes;

    public StringBody(String value) {
        this(value, null, false, null);
    }

    public StringBody(String value, Charset charset) {
        this(value, null, false, (charset != null ? DEFAULT_CONTENT_TYPE.withCharset(charset) : null));
    }

    public StringBody(String value, MediaType contentType) {
        this(value, null, false, contentType);
    }

    public StringBody(String value, byte[] rawBytes, boolean subString, MediaType contentType) {
        super(Type.STRING, contentType);
        this.value = isNotBlank(value) ? value : "";
        this.subString = subString;
        this.segmentedBytes = null;

        if (rawBytes == null && value != null) {
            this.rawBytes = encodeToRawBytes(value);
        } else {
            this.rawBytes = rawBytes;
        }
    }

    private StringBody(SegmentedBytes segmentedBytes, MediaType contentType) {
        super(Type.STRING, contentType);
        this.subString = false;
        this.rawBytes = null;
        this.segmentedBytes = segmentedBytes;
    }

    private StringBody(StringBody source) {
        super(Type.STRING, source.contentType);
        this.subString = source.subString;
        this.rawBytes = source.rawBytes != null ? source.rawBytes.clone() : null;
        this.segmentedBytes = source.segmentedBytes != null ? source.segmentedBytes.copy() : null;
        this.value = source.segmentedBytes != null ? null : cachedTextForCopy(source.value, source.rawBytes);
    }

    @Override
    public StringBody copy() {
        return withFlagsCopiedTo(new StringBody(this));
    }

    /**
     * A body of text already encoded in the charset of {@code contentType}, held in the segments it was
     * written to, so writing it needs no further copy. It equals the body built from the same text with
     * {@link #StringBody(String, MediaType)}.
     */
    public static StringBody fromSegmentedBytes(SegmentedBytes segmentedBytes, MediaType contentType) {
        if (segmentedBytes == null || contentType == null || contentType.getCharset() == null) {
            throw new IllegalArgumentException("segmented bytes need a content type with the charset they are encoded in");
        }
        return new StringBody(segmentedBytes, contentType);
    }

    public static StringBody exact(String body) {
        return new StringBody(body);
    }

    public static StringBody exact(String body, Charset charset) {
        return new StringBody(body, charset);
    }

    public static StringBody exact(String body, MediaType contentType) {
        return new StringBody(body, contentType);
    }

    public static StringBody subString(String body) {
        return new StringBody(body, null, true, null);
    }

    public static StringBody subString(String body, Charset charset) {
        return new StringBody(body, null, true, (charset != null ? DEFAULT_CONTENT_TYPE.withCharset(charset) : null));
    }

    public static StringBody subString(String body, MediaType contentType) {
        return new StringBody(body, null, true, contentType);
    }

    public String getValue() {
        String v = value;
        if (v == null && (rawBytes != null || segmentedBytes != null)) {
            v = decodeCanonicalBytes();
            value = v;
        }
        return v;
    }

    @Override
    public String getValueWithoutCaching() {
        String v = value;
        return v != null ? v : decodeCanonicalBytes();
    }

    private String decodeCanonicalBytes() {
        if (segmentedBytes != null) {
            // blank text reads as "", as it does for a body built from that text
            String decoded = decodeRawBytes(canonicalBytes());
            return isNotBlank(decoded) ? decoded : "";
        }
        return decodeRawBytes(rawBytes);
    }

    @Override
    public String toStringWithoutCaching() {
        return getValueWithoutCaching();
    }

    @Override
    public void releaseDerivedForms() {
        String v = value;
        if (v != null && rawBytes != null && v.equals(decodeRawBytes(rawBytes))) {
            value = null;
        }
    }

    @Override
    public long retainedDerivedFormBytes() {
        String v = value;
        return v == null ? 0L : (long) v.length() * 2;
    }

    /**
     * For a body built {@link #fromSegmentedBytes from segmented bytes}, a copy of them in one array.
     */
    @JsonIgnore
    public byte[] getRawBytes() {
        return canonicalBytes();
    }

    private byte[] canonicalBytes() {
        return segmentedBytes != null ? segmentedBytes.toByteArray() : rawBytes;
    }

    /**
     * The bytes a body built {@link #fromSegmentedBytes from segmented bytes} holds, or null for any other body.
     */
    @JsonIgnore
    public SegmentedBytes getSegmentedBytes() {
        return segmentedBytes;
    }

    public boolean isSubString() {
        return subString;
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
        StringBody that = (StringBody) o;
        return subString == that.subString &&
            Objects.equals(getValue(), that.getValue()) &&
            Arrays.equals(canonicalBytes(), that.canonicalBytes());
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            // keyed on the canonical rawBytes, not the releasable value view, so the hash is stable
            // across a release and never re-materialises the String
            int result = Objects.hash(super.hashCode(), subString);
            hashCode = 31 * result + Arrays.hashCode(canonicalBytes());
        }
        return hashCode;
    }
}

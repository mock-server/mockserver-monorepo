package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.serialization.Base64Converter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * @author jamesdbloom
 */
public class BinaryBody extends BodyWithContentType<byte[]> {
    private int hashCode;
    private final byte[] bytes;
    // set instead of bytes for a body built from bytes held in segments, which are never joined to be written
    private final SegmentedBytes segmentedBytes;
    private final Base64Converter base64Converter = new Base64Converter();

    public BinaryBody(byte[] bytes) {
        this(bytes, null);
    }

    public BinaryBody(byte[] bytes, MediaType contentType) {
        super(Type.BINARY, contentType);
        this.bytes = bytes;
        this.segmentedBytes = null;
    }

    private BinaryBody(SegmentedBytes segmentedBytes, MediaType contentType) {
        super(Type.BINARY, contentType);
        this.bytes = null;
        this.segmentedBytes = segmentedBytes;
    }

    @Override
    public BinaryBody copy() {
        BinaryBody copy = segmentedBytes != null
            ? new BinaryBody(segmentedBytes.copy(), contentType)
            : new BinaryBody(bytes != null ? bytes.clone() : null, contentType);
        return withFlagsCopiedTo(copy);
    }

    /**
     * A body of the bytes held in the segments they were written to, so writing it needs no further
     * copy. It equals the body built from the same bytes with {@link #BinaryBody(byte[], MediaType)}.
     */
    public static BinaryBody fromSegmentedBytes(SegmentedBytes segmentedBytes, MediaType contentType) {
        if (segmentedBytes == null) {
            throw new IllegalArgumentException("segmented bytes are required");
        }
        return new BinaryBody(segmentedBytes, contentType);
    }

    public static BinaryBody binary(byte[] body) {
        return new BinaryBody(body);
    }

    public static BinaryBody binary(byte[] body, MediaType contentType) {
        return new BinaryBody(body, contentType);
    }

    /**
     * For a body built {@link #fromSegmentedBytes from segmented bytes}, a copy of them in one array.
     */
    public byte[] getValue() {
        return canonicalBytes();
    }

    /**
     * For a body built {@link #fromSegmentedBytes from segmented bytes}, a copy of them in one array.
     */
    @JsonIgnore
    public byte[] getRawBytes() {
        return canonicalBytes();
    }

    private byte[] canonicalBytes() {
        return segmentedBytes != null ? segmentedBytes.toByteArray() : bytes;
    }

    /**
     * The bytes a body built {@link #fromSegmentedBytes from segmented bytes} holds, or null for any other body.
     */
    @JsonIgnore
    public SegmentedBytes getSegmentedBytes() {
        return segmentedBytes;
    }

    /**
     * The string body matchers and the control plane read for {@code body}. A binary body that arrived
     * with no Content-Type (bytes that are not valid UTF-8; no content type on the body or the message)
     * is read as lenient UTF-8, as such a body always was; every other body uses its normal string form,
     * which for a binary body is base64.
     */
    public static String matchableString(Body<?> body, String contentTypeHeader) {
        if (body instanceof BinaryBody && ((BinaryBody) body).getContentType() == null && body.getRawBytes() != null && isBlank(contentTypeHeader)) {
            return new String(body.getRawBytes(), StandardCharsets.UTF_8);
        }
        return body != null ? body.toString() : null;
    }

    @Override
    public String toString() {
        byte[] value = canonicalBytes();
        return value != null ? base64Converter.bytesToBase64String(value) : null;
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
        BinaryBody that = (BinaryBody) o;
        return Arrays.equals(canonicalBytes(), that.canonicalBytes()) &&
            Objects.equals(base64Converter, that.base64Converter);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            int result = Objects.hash(super.hashCode(), base64Converter);
            hashCode = 31 * result + Arrays.hashCode(canonicalBytes());
        }
        return hashCode;
    }
}

package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * @author jamesdbloom
 */
public class XmlBody extends BodyWithContentType<String> {
    private int hashCode;
    // setting default to UTF8 as per https://tools.ietf.org/html/rfc3470#section-5.1
    public static final MediaType DEFAULT_XML_CONTENT_TYPE = MediaType.APPLICATION_XML_UTF_8;
    // The raw bytes are canonical; xml is the derived String view - cached on first read and released
    // once the entry is retained (see releaseDerivedForms). The read/write race with the release is
    // benign: a reader either sees the cached String or null, and null re-derives the identical String.
    private transient String xml;
    private final byte[] rawBytes;

    public XmlBody(String xml) {
        this(xml, DEFAULT_XML_CONTENT_TYPE);
    }

    public XmlBody(String xml, Charset charset) {
        this(xml, null, (charset != null ? DEFAULT_XML_CONTENT_TYPE.withCharset(charset) : null));
    }

    public XmlBody(String xml, MediaType contentType) {
        this(xml, null, contentType);
    }

    public XmlBody(String xml, byte[] rawBytes, MediaType contentType) {
        super(Type.XML, contentType);
        this.xml = xml;

        if (rawBytes == null && xml != null) {
            this.rawBytes = encodeToRawBytes(xml);
        } else {
            this.rawBytes = rawBytes;
        }
    }

    @Override
    public XmlBody copy() {
        return withFlagsCopiedTo(new XmlBody(cachedTextForCopy(xml, rawBytes), rawBytes != null ? rawBytes.clone() : null, contentType));
    }

    public static XmlBody xml(String xml) {
        return new XmlBody(xml);
    }

    public static XmlBody xml(String xml, Charset charset) {
        return new XmlBody(xml, charset);
    }

    public static XmlBody xml(String xml, MediaType contentType) {
        return new XmlBody(xml, contentType);
    }

    public String getValue() {
        String value = xml;
        if (value == null && rawBytes != null) {
            value = decodeRawBytes(rawBytes);
            xml = value;
        }
        return value;
    }

    @Override
    public String getValueWithoutCaching() {
        String value = xml;
        return value != null ? value : decodeRawBytes(rawBytes);
    }

    @Override
    public String toStringWithoutCaching() {
        return getValueWithoutCaching();
    }

    @Override
    public void releaseDerivedForms() {
        String value = xml;
        if (value != null && rawBytes != null && value.equals(decodeRawBytes(rawBytes))) {
            xml = null;
        }
    }

    @Override
    public long retainedDerivedFormBytes() {
        String value = xml;
        return value == null ? 0L : (long) value.length() * 2;
    }

    @JsonIgnore
    public byte[] getRawBytes() {
        return rawBytes;
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
        XmlBody xmlBody = (XmlBody) o;
        return Objects.equals(getValue(), xmlBody.getValue()) &&
            Arrays.equals(rawBytes, xmlBody.rawBytes);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            // keyed on the canonical rawBytes, not the releasable xml view, so the hash is stable
            // across a release and never re-materialises the String
            int result = Objects.hash(super.hashCode());
            hashCode = 31 * result + Arrays.hashCode(rawBytes);
        }
        return hashCode;
    }
}

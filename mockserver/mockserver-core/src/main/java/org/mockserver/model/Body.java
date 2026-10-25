package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.charset.Charset;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * @author jamesdbloom
 */
public abstract class Body<T> extends Not {
    private int hashCode;
    private final Type type;
    private Boolean optional;

    public Body(Type type) {
        this.type = type;
    }

    public Type getType() {
        return type;
    }

    public Boolean getOptional() {
        return optional;
    }

    public Body<T> withOptional(Boolean optional) {
        this.optional = optional;
        return this;
    }

    public abstract T getValue();

    /**
     * A copy that shares no mutable state with this body: byte arrays, segmented bytes, parameters and
     * collections are copied, immutable values (strings, {@link NottableString}s, {@link MediaType}s) are
     * shared, and cached hashes and derived forms are not carried over. Every body is copied, even one
     * whose value is immutable, because the {@code not} and {@code optional} flags are mutable on all of them.
     * Every built-in body overrides it; a custom subclass must override it to be used with a data-plane hook.
     *
     * @throws UnsupportedOperationException for a subclass that does not override it
     */
    public Body<T> copy() {
        throw copyNotImplemented();
    }

    UnsupportedOperationException copyNotImplemented() {
        return new UnsupportedOperationException(getClass().getName() + " does not implement copy(), which a data-plane request filter or response transformer needs");
    }

    /**
     * Sets the {@code not} and {@code optional} flags of {@code copy} to this body's, for {@link #copy()}.
     */
    <B extends Body<?>> B withFlagsCopiedTo(B copy) {
        copy.not = not;
        ((Body<?>) copy).optional = optional;
        return copy;
    }

    /**
     * The same value as {@link #getValue()}, but a text body whose decoded view is not currently cached
     * decodes it afresh instead of caching it. For a reader that can run after this body's log entry was
     * retained and its derived forms released (see {@link #releaseDerivedForms()}): a caching read there
     * would re-attach a copy to the retained entry that the event-log byte budget never counts.
     */
    @JsonIgnore
    public T getValueWithoutCaching() {
        return getValue();
    }

    /**
     * {@link #toString()} without caching a decoded view; see {@link #getValueWithoutCaching()}.
     */
    @JsonIgnore
    public String toStringWithoutCaching() {
        return toString();
    }

    @JsonIgnore
    public byte[] getRawBytes() {
        return BodyTextEncoder.encode(toString(), UTF_8);
    }

    /**
     * Drop any lazily-derived, re-computable representation this body caches (the decoded String view and,
     * for {@link JsonBody}, the parsed tree) once the entry that holds it has been retained in the event
     * log. The canonical raw bytes are kept, so the string re-derives identically on the next read. No-op
     * here; only the text bodies that hold a String copy override it.
     */
    @JsonIgnore
    public void releaseDerivedForms() {
    }

    /**
     * Approximate heap, in bytes, currently held by this body's derived String view (0 when it has been
     * released or was never materialised). Counted by the event-log weigher so an entry whose String is
     * still cached is charged for it. No-op here; the text bodies override it.
     */
    @JsonIgnore
    public long retainedDerivedFormBytes() {
        return 0L;
    }

    @JsonIgnore
    public Charset getCharset(Charset defaultIfNotSet) {
        if (this instanceof BodyWithContentType) {
            return this.getCharset(defaultIfNotSet);
        }
        return defaultIfNotSet;
    }

    public String getContentType() {
        if (this instanceof BodyWithContentType) {
            return this.getContentType();
        }
        return null;
    }

    public enum Type {
        BINARY,
        JSON,
        JSON_SCHEMA,
        JSON_PATH,
        PARAMETERS,
        REGEX,
        FUZZY,
        STRING,
        XML,
        XML_SCHEMA,
        XPATH,
        JSON_RPC,
        GRAPHQL,
        LOG_EVENT,
        FILE,
        WASM,
        MULTIPART,
        ALL_OF,
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
        Body<?> body = (Body<?>) o;
        return type == body.type &&
            Objects.equals(optional, body.optional);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), type, optional);
        }
        return hashCode;
    }
}

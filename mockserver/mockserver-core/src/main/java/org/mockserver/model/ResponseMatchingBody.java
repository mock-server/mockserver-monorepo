package org.mockserver.model;

import java.util.Objects;

/**
 * Carries a request-style body matcher (regex, JSON path, JSON schema, XPath, XML schema, form parameters,
 * multipart, fuzzy, GraphQL, JSON-RPC, WASM or allOf) inside an {@link HttpResponse} that is used as a
 * response verification template. Those matchers have no content type, so they cannot be an
 * {@link HttpResponse} body directly. It is only meaningful for matching: an expectation rejects it as
 * a response to return.
 */
public class ResponseMatchingBody extends BodyWithContentType<Body<?>> {
    private int hashCode;
    private final Body<?> body;

    public ResponseMatchingBody(Body<?> body) {
        super(Objects.requireNonNull(body, "body").getType(), null);
        if (body instanceof BodyWithContentType) {
            throw new IllegalArgumentException("a " + body.getType() + " body can be used as a response body directly");
        }
        this.body = body;
    }

    /**
     * @return the wrapped body matcher
     */
    @Override
    public Body<?> getValue() {
        return body;
    }

    @Override
    public Boolean getOptional() {
        return body.getOptional();
    }

    @Override
    public Body<Body<?>> withOptional(Boolean optional) {
        body.withOptional(optional);
        return this;
    }

    @Override
    public ResponseMatchingBody copy() {
        return withFlagsCopiedTo(new ResponseMatchingBody(body.copy()));
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
        return Objects.equals(body, ((ResponseMatchingBody) o).body);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), body);
        }
        return hashCode;
    }
}

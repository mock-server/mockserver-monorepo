package org.mockserver.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A composite request-body matcher that matches only when <strong>all</strong> of its component body
 * matchers match the same request body.
 * <p>
 * This composes existing body matcher types without changing their individual semantics — for
 * example a {@code jsonPath} matcher, a {@code jsonSchema} matcher and a {@code regex} matcher can be
 * combined so a request body must satisfy every one of them. It is accepted anywhere a body matcher
 * is accepted.
 * <p>
 * An empty composition matches every request body. The composite honours the usual {@code not}
 * (negate the whole conjunction) and {@code optional} (an absent request body matches) flags; each
 * component body keeps its own {@code not} flag.
 *
 * @author jamesdbloom
 */
public class AllOfBody extends Body<List<Body>> {

    private int hashCode;
    private final List<Body> bodies;

    public AllOfBody(List<Body> bodies) {
        super(Type.ALL_OF);
        this.bodies = bodies;
    }

    public static AllOfBody allOf(Body... bodies) {
        return new AllOfBody(bodies != null ? Arrays.asList(bodies) : null);
    }

    public static AllOfBody allOf(List<Body> bodies) {
        return new AllOfBody(bodies);
    }

    public List<Body> getValue() {
        return bodies;
    }

    @Override
    public AllOfBody copy() {
        List<Body> copied = null;
        if (bodies != null) {
            copied = new ArrayList<>(bodies.size());
            for (Body body : bodies) {
                copied.add(body != null ? body.copy() : null);
            }
        }
        return withFlagsCopiedTo(new AllOfBody(copied));
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
        AllOfBody that = (AllOfBody) o;
        return Objects.equals(bodies, that.bodies);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), bodies);
        }
        return hashCode;
    }
}

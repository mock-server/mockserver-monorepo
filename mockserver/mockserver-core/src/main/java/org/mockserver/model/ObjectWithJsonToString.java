package org.mockserver.model;

import org.mockserver.serialization.ObjectMapperFactory;

/**
 * @author jamesdbloom
 */
public abstract class ObjectWithJsonToString extends ObjectWithReflectiveEqualsHashCodeToString {

    private static final String ESCAPED_QUOTE = "\"";

    /**
     * The object {@link #toString()} writes as JSON: this one, unless a subclass stands another in for it.
     */
    public Object toStringSubject() {
        return this;
    }

    @Override
    public String toString() {
        try {
            String valueAsString = ObjectMapperFactory
                .createObjectMapper(true, false)
                .writeValueAsString(toStringSubject());
            if (valueAsString.startsWith(ESCAPED_QUOTE) && valueAsString.endsWith(ESCAPED_QUOTE)) {
                valueAsString = valueAsString.substring(1, valueAsString.length() - 1);
            }
            return valueAsString;
        } catch (Exception e) {
            return super.toString();
        }
    }
}

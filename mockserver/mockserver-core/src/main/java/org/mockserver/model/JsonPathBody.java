package org.mockserver.model;

import java.util.Objects;

/**
 * @author jamesdbloom
 */
public class JsonPathBody extends Body<String> {
    private int hashCode;
    private final String jsonPath;
    private final NottableDateTimeString dateTime;

    public JsonPathBody(String jsonPath) {
        this(jsonPath, null);
    }

    /**
     * @param dateTime when not null, the body matches only when a value the JSONPath selects is a date-time
     *                 that satisfies this comparison
     */
    public JsonPathBody(String jsonPath, NottableDateTimeString dateTime) {
        super(Type.JSON_PATH);
        this.jsonPath = jsonPath;
        this.dateTime = dateTime;
    }

    public static JsonPathBody jsonPath(String jsonPath) {
        return new JsonPathBody(jsonPath);
    }

    /**
     * Matches when a value the JSONPath selects is a date-time that satisfies {@code dateTime}, for example
     * {@code jsonPath("$.order.created", after("now -1 days"))}.
     */
    public static JsonPathBody jsonPath(String jsonPath, NottableDateTimeString dateTime) {
        return new JsonPathBody(jsonPath, dateTime);
    }

    public String getValue() {
        return jsonPath;
    }

    public NottableDateTimeString getDateTime() {
        return dateTime;
    }

    @Override
    public JsonPathBody copy() {
        return withFlagsCopiedTo(new JsonPathBody(jsonPath, dateTime));
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
        JsonPathBody that = (JsonPathBody) o;
        return Objects.equals(jsonPath, that.jsonPath) && Objects.equals(dateTime, that.dateTime);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = dateTime == null ? Objects.hash(super.hashCode(), jsonPath) : Objects.hash(super.hashCode(), jsonPath, dateTime);
        }
        return hashCode;
    }
}

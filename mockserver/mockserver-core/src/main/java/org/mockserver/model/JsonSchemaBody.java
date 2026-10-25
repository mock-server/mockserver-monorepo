package org.mockserver.model;

import org.mockserver.file.FileReader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * @author jamesdbloom
 */
public class JsonSchemaBody extends Body<String> {
    private int hashCode;
    private final String jsonSchema;
    private Map<String, ParameterStyle> parameterStyles;

    public JsonSchemaBody(String jsonSchema) {
        super(Type.JSON_SCHEMA);
        this.jsonSchema = jsonSchema;
    }

    public static JsonSchemaBody jsonSchema(String jsonSchema) {
        return new JsonSchemaBody(jsonSchema);
    }

    public static JsonSchemaBody jsonSchemaFromResource(String jsonSchemaPath) {
        return new JsonSchemaBody(FileReader.readFileFromClassPathOrPath(jsonSchemaPath));
    }

    public Map<String, ParameterStyle> getParameterStyles() {
        return parameterStyles;
    }

    public JsonSchemaBody withParameterStyles(Map<String, ParameterStyle> parameterStyles) {
        this.parameterStyles = parameterStyles;
        return this;
    }

    public String getValue() {
        return jsonSchema;
    }

    @Override
    public JsonSchemaBody copy() {
        return withFlagsCopiedTo(new JsonSchemaBody(jsonSchema).withParameterStyles(parameterStyles != null ? new LinkedHashMap<>(parameterStyles) : null));
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
        JsonSchemaBody that = (JsonSchemaBody) o;
        return Objects.equals(jsonSchema, that.jsonSchema);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), jsonSchema);
        }
        return hashCode;
    }
}

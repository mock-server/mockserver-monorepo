package org.mockserver.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class GraphQLBody extends Body<String> {
    private int hashCode;
    private final String query;
    private final String operationName;
    private final String variablesSchema;
    private SelectionSetMatchType selectionSetMatchType;
    private List<String> fields;
    private String schema;

    public GraphQLBody(String query) {
        this(query, null, null);
    }

    public GraphQLBody(String query, String operationName, String variablesSchema) {
        super(Type.GRAPHQL);
        this.query = query;
        this.operationName = operationName;
        this.variablesSchema = variablesSchema;
    }

    public static GraphQLBody graphQL(String query) {
        return new GraphQLBody(query);
    }

    public static GraphQLBody graphQL(String query, String operationName) {
        return new GraphQLBody(query, operationName, null);
    }

    public static GraphQLBody graphQL(String query, String operationName, String variablesSchema) {
        return new GraphQLBody(query, operationName, variablesSchema);
    }

    public String getQuery() {
        return query;
    }

    public String getOperationName() {
        return operationName;
    }

    public String getVariablesSchema() {
        return variablesSchema;
    }

    public SelectionSetMatchType getSelectionSetMatchType() {
        return selectionSetMatchType;
    }

    public GraphQLBody withSelectionSetMatchType(SelectionSetMatchType selectionSetMatchType) {
        this.hashCode = 0;
        this.selectionSetMatchType = selectionSetMatchType;
        return this;
    }

    /**
     * The GraphQL schema (SDL text or an introspection JSON result) associated with this
     * expectation. When present, MockServer can synthesize a schema-valid response for a
     * matched query without any hand-authored response JSON.
     *
     * @return the SDL/introspection schema, or {@code null} if none is registered
     */
    public String getSchema() {
        return schema;
    }

    /**
     * Register a GraphQL schema (SDL text or an introspection JSON result) on this body so
     * that schema-valid responses can be synthesized for matched queries.
     *
     * @param schema SDL text (e.g. {@code "type Query { hello: String }"}) or an
     *               introspection JSON result (the {@code data.__schema} or full envelope)
     * @return this body for fluent chaining
     */
    public GraphQLBody withSchema(String schema) {
        this.hashCode = 0;
        this.schema = schema;
        return this;
    }

    public List<String> getFields() {
        return fields;
    }

    public GraphQLBody withFields(String... fields) {
        this.hashCode = 0;
        this.fields = fields != null ? Arrays.asList(fields) : null;
        return this;
    }

    public GraphQLBody withFields(List<String> fields) {
        this.hashCode = 0;
        this.fields = fields;
        return this;
    }

    @Override
    public String getValue() {
        return query;
    }

    @Override
    public GraphQLBody copy() {
        GraphQLBody copy = new GraphQLBody(query, operationName, variablesSchema)
            .withSelectionSetMatchType(selectionSetMatchType)
            .withFields(fields != null ? new ArrayList<>(fields) : null)
            .withSchema(schema);
        return withFlagsCopiedTo(copy);
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
        GraphQLBody that = (GraphQLBody) o;
        return Objects.equals(query, that.query) &&
            Objects.equals(operationName, that.operationName) &&
            Objects.equals(variablesSchema, that.variablesSchema) &&
            selectionSetMatchType == that.selectionSetMatchType &&
            Objects.equals(fields, that.fields) &&
            Objects.equals(schema, that.schema);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), query, operationName, variablesSchema, selectionSetMatchType, fields, schema);
        }
        return hashCode;
    }

    @Override
    public String toString() {
        return query;
    }
}

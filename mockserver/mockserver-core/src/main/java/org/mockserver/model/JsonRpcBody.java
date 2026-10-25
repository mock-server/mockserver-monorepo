package org.mockserver.model;

import java.util.Objects;

public class JsonRpcBody extends Body<String> {
    private int hashCode;
    private final String method;
    private final String paramsSchema;

    public JsonRpcBody(String method) {
        this(method, null);
    }

    public JsonRpcBody(String method, String paramsSchema) {
        super(Type.JSON_RPC);
        this.method = method;
        this.paramsSchema = paramsSchema;
    }

    public static JsonRpcBody jsonRpc(String method) {
        return new JsonRpcBody(method);
    }

    public static JsonRpcBody jsonRpc(String method, String paramsSchema) {
        return new JsonRpcBody(method, paramsSchema);
    }

    public String getMethod() {
        return method;
    }

    public String getParamsSchema() {
        return paramsSchema;
    }

    @Override
    public String getValue() {
        return method;
    }

    @Override
    public JsonRpcBody copy() {
        return withFlagsCopiedTo(new JsonRpcBody(method, paramsSchema));
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
        JsonRpcBody that = (JsonRpcBody) o;
        return Objects.equals(method, that.method) &&
            Objects.equals(paramsSchema, that.paramsSchema);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), method, paramsSchema);
        }
        return hashCode;
    }

    @Override
    public String toString() {
        return method;
    }
}

package org.mockserver.model;

import java.util.Objects;

/**
 * Body matcher that delegates matching to a WASM module stored in the {@link org.mockserver.wasm.WasmStore}.
 * The WASM module must export a {@code match(i32 ptr, i32 len) -> i32} function.
 */
public class WasmBody extends Body<String> {

    private int hashCode;
    private final String moduleName;

    public WasmBody(String moduleName) {
        super(Type.WASM);
        this.moduleName = moduleName;
    }

    public static WasmBody wasmBody(String moduleName) {
        return new WasmBody(moduleName);
    }

    public String getModuleName() {
        return moduleName;
    }

    @Override
    public String getValue() {
        return moduleName;
    }

    @Override
    public WasmBody copy() {
        return withFlagsCopiedTo(new WasmBody(moduleName));
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
        WasmBody wasmBody = (WasmBody) o;
        return Objects.equals(moduleName, wasmBody.moduleName);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), moduleName);
        }
        return hashCode;
    }
}

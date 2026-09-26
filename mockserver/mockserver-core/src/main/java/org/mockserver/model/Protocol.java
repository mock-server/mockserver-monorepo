package org.mockserver.model;

public enum Protocol {
    HTTP_1_1, HTTP_2, HTTP_3;

    // Precomputed once per constant: the matcher wraps the protocol name in a NottableString for
    // every candidate that reaches the protocol field, even when the expectation does not constrain
    // protocol. NottableString is immutable, so the same instance is safe to share across requests
    // and threads, turning a per-candidate allocation into a constant read.
    private final NottableString nottableName = NottableString.string(name());

    public NottableString getNottableName() {
        return nottableName;
    }
}

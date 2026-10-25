package org.mockserver.extension;

import org.mockserver.model.Protocol;

/**
 * Read-only facts about the request a {@link RequestFilter} is given. <b>Experimental.</b>
 */
public class FilterContext {

    private final String correlationId;
    private final Protocol protocol;
    private final boolean secure;
    private final String remoteAddress;
    private final boolean proxyRequest;
    private final boolean corsPreflight;
    private final boolean grpc;

    FilterContext(String correlationId, Protocol protocol, boolean secure, String remoteAddress, boolean proxyRequest, boolean corsPreflight, boolean grpc) {
        this.correlationId = correlationId;
        this.protocol = protocol;
        this.secure = secure;
        this.remoteAddress = remoteAddress;
        this.proxyRequest = proxyRequest;
        this.corsPreflight = corsPreflight;
        this.grpc = grpc;
    }

    FilterContext(FilterContext other) {
        this(other.correlationId, other.protocol, other.secure, other.remoteAddress, other.proxyRequest, other.corsPreflight, other.grpc);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the id MockServer's log entries for this request carry
     */
    public String correlationId() {
        return correlationId;
    }

    /**
     * @return the HTTP version the request arrived on, or {@code null} when not known
     */
    public Protocol protocol() {
        return protocol;
    }

    /**
     * @return true when the request arrived over TLS
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * @return the client's address, or {@code null} when not known (in-process)
     */
    public String remoteAddress() {
        return remoteAddress;
    }

    /**
     * @return true when the request was sent to MockServer as a proxy
     */
    public boolean isProxyRequest() {
        return proxyRequest;
    }

    /**
     * @return true when the request is a CORS preflight ({@code OPTIONS} with {@code Access-Control-Request-Method}),
     * which a filter that checks credentials should normally let through
     */
    public boolean isCorsPreflight() {
        return corsPreflight;
    }

    /**
     * @return true for a gRPC request, whose path and {@code x-grpc-*} headers a filter must not change
     */
    public boolean isGrpc() {
        return grpc;
    }

    @Override
    public String toString() {
        return "correlationId=" + correlationId + ", protocol=" + protocol + ", secure=" + secure + ", remoteAddress=" + remoteAddress
            + ", proxyRequest=" + proxyRequest + ", corsPreflight=" + corsPreflight + ", grpc=" + grpc;
    }

    /**
     * Builds a {@link FilterContext}, or with {@link TransformContext#of} a {@link TransformContext}; MockServer builds
     * these from the transport, and a test builds its own.
     */
    public static final class Builder {
        private String correlationId;
        private Protocol protocol;
        private boolean secure;
        private String remoteAddress;
        private boolean proxyRequest;
        private boolean corsPreflight;
        private boolean grpc;

        private Builder() {
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder protocol(Protocol protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder secure(boolean secure) {
            this.secure = secure;
            return this;
        }

        public Builder remoteAddress(String remoteAddress) {
            this.remoteAddress = remoteAddress;
            return this;
        }

        public Builder proxyRequest(boolean proxyRequest) {
            this.proxyRequest = proxyRequest;
            return this;
        }

        public Builder corsPreflight(boolean corsPreflight) {
            this.corsPreflight = corsPreflight;
            return this;
        }

        public Builder grpc(boolean grpc) {
            this.grpc = grpc;
            return this;
        }

        public FilterContext build() {
            return new FilterContext(correlationId, protocol, secure, remoteAddress, proxyRequest, corsPreflight, grpc);
        }
    }
}

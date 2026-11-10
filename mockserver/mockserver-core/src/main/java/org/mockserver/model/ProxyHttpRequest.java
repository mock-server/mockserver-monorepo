package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.authentication.ProxyAuthenticationContext;

/** Transport-only proxy identity and routing state; absent from ordinary requests and request JSON. */
final class ProxyHttpRequest extends HttpRequest {
    private final ProxyAuthenticationContext authentication;
    private final boolean dataPlaneRequest;

    ProxyHttpRequest(ProxyAuthenticationContext authentication, boolean dataPlaneRequest) {
        this.authentication = authentication;
        this.dataPlaneRequest = dataPlaneRequest;
    }

    @Override
    @JsonIgnore
    public ProxyAuthenticationContext getProxyAuthenticationContext() {
        return authentication;
    }

    @Override
    @JsonIgnore
    public boolean isDataPlaneRequest() {
        return dataPlaneRequest;
    }

    @Override
    HttpRequest emptyCopy(boolean deep) {
        return new ProxyHttpRequest(deep && authentication != null
            ? new ProxyAuthenticationContext(authentication.username()) : authentication, dataPlaneRequest);
    }
}

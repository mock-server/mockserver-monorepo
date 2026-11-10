package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.authentication.ProxyAuthenticationContext;

/**
 * The copy of a request the forward client adds {@code forwardCredentialHeadersByHost} credentials to (see
 * {@link HttpRequest#cloneToAddForwardCredentials()}). It holds the request they were added to, which it is printed
 * and logged as; a subclass, so that no other request carries the reference. Its clones and copies are of this class.
 */
final class CredentialedForwardRequest extends HttpRequest {

    private final HttpRequest requestBeforeForwardCredentials;

    CredentialedForwardRequest(HttpRequest requestBeforeForwardCredentials) {
        this.requestBeforeForwardCredentials = requestBeforeForwardCredentials;
    }

    @Override
    @JsonIgnore
    public HttpRequest getRequestBeforeForwardCredentials() {
        return requestBeforeForwardCredentials;
    }

    /**
     * The request before the credentials were added, so neither {@link #toString()} nor a log message built from this
     * copy shows them.
     */
    @Override
    public Object toStringSubject() {
        return requestBeforeForwardCredentials;
    }

    @Override
    @JsonIgnore
    public ProxyAuthenticationContext getProxyAuthenticationContext() {
        return requestBeforeForwardCredentials.getProxyAuthenticationContext();
    }

    @Override
    @JsonIgnore
    public boolean isDataPlaneRequest() {
        return requestBeforeForwardCredentials.isDataPlaneRequest();
    }

    @Override
    HttpRequest emptyCopy(boolean deep) {
        return new CredentialedForwardRequest(deep ? requestBeforeForwardCredentials.deepCopy() : requestBeforeForwardCredentials);
    }
}

package org.mockserver.lifecycle;

import org.mockserver.mock.HttpState;

/**
 * Gives a test outside this package the state of a running server, which {@link LifeCycle} keeps protected.
 */
public final class HttpStateTestAccess {

    private HttpStateTestAccess() {
    }

    public static HttpState httpState(LifeCycle server) {
        return server.httpState;
    }
}

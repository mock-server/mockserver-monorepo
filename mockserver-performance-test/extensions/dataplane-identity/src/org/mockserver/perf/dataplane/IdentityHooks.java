package org.mockserver.perf.dataplane;

import org.mockserver.model.HttpRequest;

final class IdentityHooks {

    static final String PROBE_PATH = "/dp_ab/probe";
    static final String PROBE_HEADER = "x-dp-ab-hook";

    private IdentityHooks() {
    }

    static boolean isProbe(HttpRequest request) {
        return request.getPath() != null && PROBE_PATH.equals(request.getPath().getValue());
    }
}

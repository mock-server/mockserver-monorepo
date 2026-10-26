package org.mockserver.perf.dataplane;

import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

/**
 * The perf A/B's identity response transformer: it returns the copy it is given, unchanged. Only the response to
 * {@link IdentityHooks#PROBE_PATH} is marked, so the harness can prove this transformer is the one loaded.
 */
public class IdentityResponseTransformer implements ResponseTransformer {

    @Override
    public String name() {
        return "perf-identity-transformer";
    }

    @Override
    public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) {
        if (IdentityHooks.isProbe(request)) {
            return response.withHeader(IdentityHooks.PROBE_HEADER, "response-transformer");
        }
        return response;
    }
}

package org.mockserver.perf.dataplane;

import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.model.HttpRequest;

import static org.mockserver.model.HttpResponse.response;

/**
 * The perf A/B's identity request filter: it hands back the copy it is given, unchanged, so MockServer pays the whole
 * accept path (copy, normalise, compare by value) and nothing else. Only {@link IdentityHooks#PROBE_PATH} is answered,
 * so the harness can prove this filter is the one loaded.
 */
public class IdentityRequestFilter implements RequestFilter {

    @Override
    public String name() {
        return "perf-identity-filter";
    }

    @Override
    public FilterResult filter(HttpRequest request, FilterContext context) {
        if (IdentityHooks.isProbe(request)) {
            return FilterResult.respond(response().withStatusCode(200).withHeader(IdentityHooks.PROBE_HEADER, "request-filter"));
        }
        return FilterResult.proceedWith(request);
    }
}

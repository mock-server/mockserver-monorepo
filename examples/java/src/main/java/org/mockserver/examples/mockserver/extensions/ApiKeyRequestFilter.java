package org.mockserver.examples.mockserver.extensions;

import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.model.HttpRequest;

import static org.mockserver.model.HttpResponse.response;

public class ApiKeyRequestFilter implements RequestFilter {

    @Override
    public String name() {
        return "api-key";
    }

    @Override
    public FilterResult filter(HttpRequest request, FilterContext context) {
        if (context.isCorsPreflight()) {
            // a browser sends its CORS preflight without the key, so let it through
            return FilterResult.proceed();
        }
        if (!"let-me-in".equals(request.getFirstHeader("X-Api-Key"))) {
            // answer now: no expectation is matched and no later filter runs
            return FilterResult.respond(response().withStatusCode(401).withBody("missing or wrong X-Api-Key"));
        }
        // continue without the key, so expectations and recorded requests never show it
        return FilterResult.proceedWith(request.removeHeader("X-Api-Key"));
    }
}

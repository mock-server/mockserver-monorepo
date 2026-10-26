package org.mockserver.examples.mockserver.extensions;

import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import static org.mockserver.model.JsonBody.json;

public class ServedByResponseTransformer implements ResponseTransformer {

    @Override
    public String name() {
        return "served-by";
    }

    @Override
    public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) {
        response.replaceHeader("X-Served-By", "mockserver");
        if (context.isGenerated() && Integer.valueOf(404).equals(response.getStatusCode())) {
            // MockServer's own "no expectation matched" response gets a JSON body; leave Content-Length
            // alone and MockServer sets it to the length of the new body
            response.withBody(json("{\"error\": \"no expectation matched this request\"}"));
        }
        return response;
    }
}

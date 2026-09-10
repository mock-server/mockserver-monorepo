package org.mockserver.netty;

import com.google.common.io.ByteStreams;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;

/**
 * Serves the embedded OpenAPI specification at GET /mockserver/openapi.yaml
 */
public class OpenAPISpecHandler {

    private static final String OPENAPI_SPEC_RESOURCE = "/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml";
    private static final String CONTENT_TYPE_YAML = "application/yaml; charset=utf-8";

    private static volatile String cachedSpec;

    public void renderOpenAPISpec(final ChannelHandlerContext ctx, final HttpRequest request) throws Exception {
        HttpResponse response = notFoundResponse();
        if (request.getMethod().getValue().equals("GET")) {
            String spec = getSpec();
            if (spec != null) {
                response =
                    response()
                        .withStatusCode(200)
                        .withHeader(HttpHeaderNames.CONTENT_TYPE.toString(), CONTENT_TYPE_YAML)
                        .withHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), String.valueOf(spec.getBytes(UTF_8).length))
                        .withBody(spec);
                if (Boolean.TRUE.equals(request.isKeepAlive())) {
                    response.withHeader(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.KEEP_ALIVE.toString());
                }
            }
        }
        // Like DashboardHandler this writes straight to the channel rather than through
        // ResponseWriter, so nothing has copied the request's HTTP/2 stream id onto the response -
        // and the response mapper reads that field, not a header. Without it the head is routed
        // onto a fresh server-initiated stream instead of the client's and the client hangs. Null
        // on HTTP/1.1, where it is a no-op.
        response.withStreamId(request.getStreamId());
        if (Boolean.TRUE.equals(request.isKeepAlive())) {
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String getSpec() throws Exception {
        if (cachedSpec == null) {
            synchronized (OpenAPISpecHandler.class) {
                if (cachedSpec == null) {
                    try (InputStream inputStream = OpenAPISpecHandler.class.getResourceAsStream(OPENAPI_SPEC_RESOURCE)) {
                        if (inputStream != null) {
                            cachedSpec = new String(ByteStreams.toByteArray(inputStream), UTF_8.name());
                        }
                    }
                }
            }
        }
        return cachedSpec;
    }

}

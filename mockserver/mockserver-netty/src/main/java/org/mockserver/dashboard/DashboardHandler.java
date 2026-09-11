package org.mockserver.dashboard;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.substringAfter;
import static org.apache.commons.lang3.StringUtils.substringAfterLast;
import static org.mockserver.mock.HttpState.PATH_PREFIX;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class DashboardHandler {

    private static final Map<String, String> MIME_MAP = new HashMap<>();
    private static final List<String> IS_STRING_CONTENT = ImmutableList.of(
        "css",
        "js",
        "map",
        "json",
        "html"
    );

    public DashboardHandler() {
        MIME_MAP.put("css", "text/css; charset=utf-8");
        MIME_MAP.put("js", "application/javascript; charset=UTF-8");
        MIME_MAP.put("map", "application/json; charset=UTF-8");
        MIME_MAP.put("json", "application/json; charset=UTF-8");
        MIME_MAP.put("html", "text/html; charset=utf-8");
        MIME_MAP.put("ico", "image/x-icon");
        MIME_MAP.put("svg", "image/svg+xml");
        MIME_MAP.put("woff2", "application/font-woff2");
        MIME_MAP.put("ttf", "application/octet-stream");
        MIME_MAP.put("png", "image/png");
    }

    // Fallback for any asset extension not explicitly mapped above. Returning a non-null
    // Content-Type for every served asset is essential: a null header value would later
    // be rejected by Netty's header encoder (NullPointerException), failing the response.
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    public void renderDashboard(final ChannelHandlerContext ctx, final HttpRequest request) throws Exception {
        HttpResponse response = notFoundResponse();
        if (request.getMethod().getValue().equals("GET")) {
            String path = substringAfter(request.getPath().getValue(), PATH_PREFIX + "/dashboard");
            if (path.isEmpty() || path.equals("/")) {
                path = "/index.html";
            }
            if (path.contains("..")) {
                ctx.writeAndFlush(notFoundResponse().withStreamId(request.getStreamId())).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            String resourcePath = "/org/mockserver/dashboard" + path;
            String normalizedPath = java.net.URI.create(resourcePath).normalize().getPath();
            if (!normalizedPath.startsWith("/org/mockserver/dashboard/") && !normalizedPath.equals("/org/mockserver/dashboard")) {
                ctx.writeAndFlush(notFoundResponse().withStreamId(request.getStreamId())).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            try (InputStream contentStream = DashboardHandler.class.getResourceAsStream(normalizedPath)) {
                if (contentStream != null) {
                    final String extension = substringAfterLast(path, ".");
                    final String contentType = MIME_MAP.getOrDefault(extension, DEFAULT_MIME_TYPE);
                    if (IS_STRING_CONTENT.contains(extension)) {
                        final String content = new String(ByteStreams.toByteArray(contentStream), UTF_8.name());
                        response =
                            response()
                                .withHeader(HttpHeaderNames.CONTENT_TYPE.toString(), contentType)
                                .withHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), String.valueOf(content.getBytes(UTF_8).length))
                                .withBody(content);
                    } else {
                        final byte[] bytes = ByteStreams.toByteArray(contentStream);
                        response =
                            response()
                                .withHeader(HttpHeaderNames.CONTENT_TYPE.toString(), contentType)
                                .withHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), String.valueOf(bytes.length))
                                .withBody(bytes);
                    }
                    if (request.isKeepAlive()) {
                        response.withHeader(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.KEEP_ALIVE.toString());
                    }
                }
            }
        }
        // This handler writes directly to the channel (it does not go through ResponseWriter), so
        // nothing has copied the request's HTTP/2 stream id onto the response - and the HTTP/2 response
        // mapper reads that field rather than a header. Copy it here so the response head is associated
        // with the client's own stream (see Http2StreamIds for how the id is captured inbound). Null on
        // HTTP/1.1, where it is a no-op.
        response.withStreamId(request.getStreamId());
        if (!request.isKeepAlive()) {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.writeAndFlush(response);
        }
    }
}

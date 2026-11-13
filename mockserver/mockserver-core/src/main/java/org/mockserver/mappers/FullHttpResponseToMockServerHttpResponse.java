package org.mockserver.mappers;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import org.mockserver.codec.BodyDecoderEncoder;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.slf4j.event.Level;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static org.mockserver.model.NottableString.headerName;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.NottableString.strings;

/**
 * @author jamesdbloom
 */
public class FullHttpResponseToMockServerHttpResponse {

    private final MockServerLogger mockServerLogger;
    private final BodyDecoderEncoder bodyDecoderEncoder;

    public FullHttpResponseToMockServerHttpResponse(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
        this.bodyDecoderEncoder = new BodyDecoderEncoder();
    }

    public HttpResponse mapFullHttpResponseToMockServerResponse(FullHttpResponse fullHttpResponse) {
        HttpResponse httpResponse = new HttpResponse();
        try {
            if (fullHttpResponse != null) {
                if (fullHttpResponse.decoderResult().isFailure()) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setHttpResponse(NettyMessageForLog.response(fullHttpResponse))
                            .setMessageFormat("exception decoding response " + fullHttpResponse.decoderResult().cause().getMessage())
                            .setThrowable(fullHttpResponse.decoderResult().cause())
                    );
                }
                setStatusCode(httpResponse, fullHttpResponse);
                setHeaders(httpResponse, fullHttpResponse);
                setCookies(httpResponse);
                setBody(httpResponse, fullHttpResponse);
            }
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setHttpResponse(NettyMessageForLog.response(fullHttpResponse))
                    .setMessageFormat("exception decoding response{}")
                    .setArguments(fullHttpResponse)
                    .setThrowable(throwable)
            );
        }
        return httpResponse;
    }

    private void setStatusCode(HttpResponse httpResponse, FullHttpResponse fullHttpResponse) {
        HttpResponseStatus status = fullHttpResponse.status();
        httpResponse.withStatusCode(status.code());
        httpResponse.withReasonPhrase(status.reasonPhrase());
    }

    private void setHeaders(HttpResponse httpResponse, FullHttpResponse fullHttpResponse) {
        Headers headers = new Headers();
        Set<String> seen = new HashSet<>();
        for (String headerName : fullHttpResponse.headers().names()) {
            // Skip the Netty HTTP/2 extension-header family (x-http2-*). These are injected by
            // InboundHttp2ToHttpAdapter when decoding an HTTP/2 response and are internal plumbing
            // rather than real response headers; letting them into the model leaks the upstream
            // stream id back onto the client leg (PROTOCOL_ERROR / GOAWAY hang) and pollutes logs.
            if (Http2ExtensionHeaders.isExtensionHeader(headerName)) {
                continue;
            }
            // Literal name and values: this is an actual upstream response, so a header genuinely
            // named "!foo" (or valued "!foo") must be recorded verbatim, not read as a negation
            // matcher. The withEntry(String, ...) overloads route through NottableString.string(name),
            // which strips a leading !/? - correct for matcher input, wrong for a real message.
            headers.withEntry(headerName(headerName), strings(fullHttpResponse.headers().getAll(headerName), false));
            seen.add(headerName.toLowerCase(Locale.ROOT));
        }
        // HTTP/2 (and chunked HTTP/1.1) trailers. A gRPC response keeps them as trailers: they carry
        // grpc-status and the trailing metadata, which a client reads only from the trailers, and the
        // gRPC paths read the status from either place. Any other response has its trailers folded
        // into the headers, unless a header of the same name is present, so none is dropped.
        // The x-http2-* plumbing is skipped here too.
        boolean hasTrailers = !fullHttpResponse.trailingHeaders().isEmpty();
        boolean grpc = hasTrailers && GrpcStatusMapper.isGrpcContentType(fullHttpResponse.headers().get(CONTENT_TYPE));
        Headers trailers = grpc ? new Headers() : null;
        for (String trailerName : fullHttpResponse.trailingHeaders().names()) {
            if (Http2ExtensionHeaders.isExtensionHeader(trailerName)) {
                continue;
            }
            if (grpc) {
                trailers.withEntry(headerName(trailerName), strings(fullHttpResponse.trailingHeaders().getAll(trailerName), false));
                continue;
            }
            if (seen.contains(trailerName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            headers.withEntry(headerName(trailerName), strings(fullHttpResponse.trailingHeaders().getAll(trailerName), false));
        }
        if (!headers.isEmpty()) {
            httpResponse.withHeaders(headers);
        }
        if (trailers != null && !trailers.isEmpty()) {
            httpResponse.withTrailers(trailers);
        }
    }

    private void setCookies(HttpResponse httpResponse) {
        Cookies cookies = new Cookies();
        for (Header header : httpResponse.getHeaderList()) {
            if (header.getName().getValue().equalsIgnoreCase("Set-Cookie")) {
                for (NottableString cookieHeader : header.getValues()) {
                    // null for a value with no name or no "=": the header is relayed as received, with no cookie for it
                    io.netty.handler.codec.http.cookie.Cookie httpCookie = ClientCookieDecoder.LAX.decode(cookieHeader.getValue());
                    if (httpCookie != null) {
                        String name = httpCookie.name().trim();
                        String value = httpCookie.value() != null ? httpCookie.value().trim() : "";
                        cookies.withEntry(new Cookie(string(name, false), string(value, false)));
                    }
                }
            }
            if (header.getName().getValue().equalsIgnoreCase("Cookie")) {
                for (NottableString cookieHeader : header.getValues()) {
                    for (io.netty.handler.codec.http.cookie.Cookie httpCookie : ServerCookieDecoder.LAX.decode(cookieHeader.getValue())) {
                        String name = httpCookie.name().trim();
                        String value = httpCookie.value() != null ? httpCookie.value().trim() : "";
                        cookies.withEntry(new Cookie(string(name, false), string(value, false)));
                    }
                }
            }
        }
        if (!cookies.isEmpty()) {
            httpResponse.withCookies(cookies);
        }
    }

    private void setBody(HttpResponse httpResponse, FullHttpResponse fullHttpResponse) {
        httpResponse.withBody(bodyDecoderEncoder.byteBufToBody(fullHttpResponse.content(), fullHttpResponse.headers().get(CONTENT_TYPE)));
    }
}

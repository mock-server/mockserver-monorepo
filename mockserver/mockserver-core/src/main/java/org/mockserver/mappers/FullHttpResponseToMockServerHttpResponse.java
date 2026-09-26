package org.mockserver.mappers;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http2.HttpConversionUtil;
import org.mockserver.codec.BodyDecoderEncoder;
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

    /**
     * The Netty HTTP/2 -&gt; HTTP/1.1 extension header family (see
     * {@link HttpConversionUtil.ExtensionHeaderNames}). When an upstream response is decoded from
     * HTTP/2, {@code InboundHttp2ToHttpAdapter} injects synthetic {@code x-http2-*} headers -- most
     * notably {@code x-http2-stream-id} carrying the UPSTREAM stream id. These are Netty-internal
     * plumbing, not real response headers, so they must never enter the response model: if
     * {@code x-http2-stream-id} leaks through it is re-emitted on write-back and makes the outbound
     * response to an HTTP/2 client carry a foreign stream id, triggering a PROTOCOL_ERROR / GOAWAY
     * and hanging the client. They are also noise in captured/logged responses.
     */
    private static final Set<String> HTTP2_EXTENSION_HEADER_NAMES = buildHttp2ExtensionHeaderNames();

    private static Set<String> buildHttp2ExtensionHeaderNames() {
        Set<String> names = new HashSet<>();
        names.add(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text().toString().toLowerCase(Locale.ROOT));
        names.add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text().toString().toLowerCase(Locale.ROOT));
        names.add(HttpConversionUtil.ExtensionHeaderNames.PATH.text().toString().toLowerCase(Locale.ROOT));
        names.add(HttpConversionUtil.ExtensionHeaderNames.STREAM_DEPENDENCY_ID.text().toString().toLowerCase(Locale.ROOT));
        names.add(HttpConversionUtil.ExtensionHeaderNames.STREAM_WEIGHT.text().toString().toLowerCase(Locale.ROOT));
        names.add(HttpConversionUtil.ExtensionHeaderNames.STREAM_PROMISE_ID.text().toString().toLowerCase(Locale.ROOT));
        return names;
    }

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
            if (HTTP2_EXTENSION_HEADER_NAMES.contains(headerName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            // Literal name and values: this is an actual upstream response, so a header genuinely
            // named "!foo" (or valued "!foo") must be recorded verbatim, not read as a negation
            // matcher. The withEntry(String, ...) overloads route through NottableString.string(name),
            // which strips a leading !/? - correct for matcher input, wrong for a real message.
            headers.withEntry(headerName(headerName), strings(fullHttpResponse.headers().getAll(headerName), false));
            seen.add(headerName.toLowerCase(Locale.ROOT));
        }
        // Fold in HTTP/2 (and chunked HTTP/1.1) TRAILERS. Real gRPC servers deliver the terminal
        // grpc-status / grpc-message in trailers, which land in FullHttpResponse.trailingHeaders()
        // and would otherwise be dropped -- silently relaying (and recording) a non-OK upstream RPC
        // as OK. A trailer is folded in only when a header of the same name is not already present,
        // so ordinary responses (which rarely carry trailers) are unaffected and no value is
        // duplicated. The x-http2-* plumbing is skipped here too.
        for (String trailerName : fullHttpResponse.trailingHeaders().names()) {
            if (HTTP2_EXTENSION_HEADER_NAMES.contains(trailerName.toLowerCase(Locale.ROOT))) {
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
    }

    private void setCookies(HttpResponse httpResponse) {
        Cookies cookies = new Cookies();
        for (Header header : httpResponse.getHeaderList()) {
            if (header.getName().getValue().equalsIgnoreCase("Set-Cookie")) {
                for (NottableString cookieHeader : header.getValues()) {
                    io.netty.handler.codec.http.cookie.Cookie httpCookie = ClientCookieDecoder.LAX.decode(cookieHeader.getValue());
                    String name = httpCookie.name().trim();
                    String value = httpCookie.value() != null ? httpCookie.value().trim() : "";
                    cookies.withEntry(new Cookie(string(name, false), string(value, false)));
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

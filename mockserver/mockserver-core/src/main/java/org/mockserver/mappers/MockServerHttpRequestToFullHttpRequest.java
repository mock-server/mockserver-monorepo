package org.mockserver.mappers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import org.mockserver.codec.BodyContentEncodingEncoder;
import org.mockserver.codec.BodyDecoderEncoder;
import org.mockserver.codec.ForwardedAcceptEncoding;
import org.mockserver.codec.SnappyBlockOrFrameDecoder;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.*;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpHeaderValues.*;
import static io.netty.handler.codec.http.HttpUtil.isKeepAlive;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * @author jamesdbloom
 */
public class MockServerHttpRequestToFullHttpRequest {

    private final MockServerLogger mockServerLogger;
    private final Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations;
    private final BodyDecoderEncoder bodyDecoderEncoder;

    public MockServerHttpRequestToFullHttpRequest(MockServerLogger mockServerLogger, Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations) {
        this.mockServerLogger = mockServerLogger;
        this.proxyConfigurations = proxyConfigurations;
        this.bodyDecoderEncoder = new BodyDecoderEncoder();
    }

    public FullHttpRequest mapMockServerRequestToNettyRequest(HttpRequest httpRequest) {
        // method
        HttpMethod httpMethod = HttpMethod.valueOf(httpRequest.getMethod("GET"));
        try {
            // the request
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, httpMethod, getURI(httpRequest, proxyConfigurations), getBody(httpRequest));

            // headers
            setHeader(httpRequest, request);

            // cookies
            setCookies(httpRequest, request);

            return request;
        } catch (Throwable throwable) {
            // never the forward client's copy, which carries forwardCredentialHeadersByHost credentials
            HttpRequest requestForLog = httpRequest.getRequestBeforeForwardCredentials() != null ? httpRequest.getRequestBeforeForwardCredentials() : httpRequest;
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setHttpRequestIfPresent(requestForLog)
                    .setMessageFormat("exception encoding request{}")
                    .setArguments(requestForLog)
                    .setThrowable(throwable)
            );
            return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, httpMethod, getURI(httpRequest, proxyConfigurations));
        }
    }

    @SuppressWarnings("HttpUrlsUsage")
    public String getURI(HttpRequest httpRequest, Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations) {
        String uri = "";
        if (httpRequest.getPath() != null) {
            if (httpRequest.getQueryStringParameters() != null && isNotBlank(httpRequest.getQueryStringParameters().getRawParameterString())) {
                uri = httpRequest.getPath().getValue() + "?" + httpRequest.getQueryStringParameters().getRawParameterString();
            } else {
                QueryStringEncoder queryStringEncoder = new QueryStringEncoder(httpRequest.getPath().getValue());
                for (Parameter parameter : httpRequest.getQueryStringParameterList()) {
                    for (NottableString value : parameter.getValues()) {
                        queryStringEncoder.addParam(parameter.getName().getValue(), value.getValue());
                    }
                }
                uri = queryStringEncoder.toString();
            }
        }
        if (proxyConfigurations != null && proxyConfigurations.get(ProxyConfiguration.Type.HTTP) != null && !Boolean.TRUE.equals(httpRequest.isSecure())) {
            if (isNotBlank(httpRequest.getFirstHeader(HOST.toString()))) {
                uri = "http://" + httpRequest.getFirstHeader(HOST.toString()) + uri;
            } else if (httpRequest.getRemoteAddress() != null) {
                uri = "http://" + httpRequest.getRemoteAddress() + uri;
            }
        }
        return uri;
    }

    private ByteBuf getBody(HttpRequest httpRequest) {
        if (httpRequest.isBodyAsReceived()) {
            // unchanged since it was received with a Content-Encoding, so send the bytes the client sent
            return Unpooled.wrappedBuffer(httpRequest.getBodyAsOriginalRawBytes());
        }
        ByteBuf bodyByteBuf = bodyDecoderEncoder.bodyToByteBuf(httpRequest.getBody(), httpRequest.getFirstHeader(CONTENT_TYPE.toString()));
        // the first value, as the inbound decompressor reads it, so the encoding is the inverse of the decoding
        String contentEncoding = httpRequest.getFirstHeader(CONTENT_ENCODING.toString());
        if (bodyByteBuf.readableBytes() == 0 || isBlank(contentEncoding)) {
            return bodyByteBuf;
        }
        byte[] decodedBody = new byte[bodyByteBuf.readableBytes()];
        bodyByteBuf.readBytes(decodedBody);
        bodyByteBuf.release();
        byte[] encoded = BodyContentEncodingEncoder.encodeBody(decodedBody, contentEncoding, SnappyBlockOrFrameDecoder.isFramed(httpRequest.getOriginalBody()));
        return Unpooled.wrappedBuffer(encoded);
    }

    private void setCookies(HttpRequest httpRequest, FullHttpRequest request) {
        if (!httpRequest.getCookieList().isEmpty()) {
            List<io.netty.handler.codec.http.cookie.Cookie> cookies = new ArrayList<>();
            for (org.mockserver.model.Cookie cookie : httpRequest.getCookieList()) {
                cookies.add(new io.netty.handler.codec.http.cookie.DefaultCookie(cookie.getName().getValue(), cookie.getValue().getValue()));
            }
            request.headers().set(COOKIE.toString(), io.netty.handler.codec.http.cookie.ClientCookieEncoder.LAX.encode(cookies));
        }
    }

    private void setHeader(HttpRequest httpRequest, FullHttpRequest request) {
        List<String> acceptEncoding = new ArrayList<>();
        for (Header header : httpRequest.getHeaderList()) {
            String headerName = header.getName().getValue();
            if (headerName.equalsIgnoreCase(ACCEPT_ENCODING.toString())) {
                if (header.getValues().isEmpty()) {
                    acceptEncoding.add("");
                }
                for (NottableString headerValue : header.getValues()) {
                    acceptEncoding.add(headerValue.getValue());
                }
            }
            // do not set hop-by-hop headers, and never leak the x-mockserver-response-index control header
            // (force-response-variant) upstream — it is consumed at action-resolution time and is meaningful
            // only to MockServer. Filtering it out of the outbound request here (rather than mutating the
            // model) keeps it in recorded traffic while ensuring forwards/proxies never carry it.
            if (!headerName.equalsIgnoreCase(CONTENT_LENGTH.toString())
                && !headerName.equalsIgnoreCase(TRANSFER_ENCODING.toString())
                && !headerName.equalsIgnoreCase(HOST.toString())
                && !headerName.equalsIgnoreCase(ACCEPT_ENCODING.toString())
                && !headerName.equalsIgnoreCase(org.mockserver.mock.Expectation.FORCE_RESPONSE_INDEX_HEADER)) {
                if (!header.getValues().isEmpty()) {
                    for (NottableString headerValue : header.getValues()) {
                        request.headers().add(headerName, headerValue.getValue());
                    }
                } else {
                    request.headers().add(headerName, "");
                }
            }
        }

        if (isNotBlank(httpRequest.getFirstHeader(HOST.toString()))) {
            request.headers().add(HOST, httpRequest.getFirstHeader(HOST.toString()));
        }
        // the client's codings, less any the forward client could not decode on the way back
        String forwardedAcceptEncoding = ForwardedAcceptEncoding.forwarded(acceptEncoding);
        if (forwardedAcceptEncoding != null) {
            request.headers().set(ACCEPT_ENCODING, forwardedAcceptEncoding);
        }
        // no x-http2-scheme or x-http2-stream-id: the HTTP/2 forward's stream codec sets the scheme and stream itself,
        // and when ALPN settles on HTTP/1.1 they would reach the upstream as headers
        request.headers().set(CONTENT_LENGTH, request.content().readableBytes());
        if (isKeepAlive(request)) {
            request.headers().set(CONNECTION, KEEP_ALIVE);
        } else {
            request.headers().set(CONNECTION, CLOSE);
        }

        if (!request.headers().contains(CONTENT_TYPE)) {
            if (httpRequest.getBody() != null
                && httpRequest.getBody().getContentType() != null) {
                request.headers().set(CONTENT_TYPE, httpRequest.getBody().getContentType());
            }
        }
    }
}

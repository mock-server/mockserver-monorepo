package org.mockserver.mappers;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http2.HttpConversionUtil;
import org.apache.commons.lang3.Strings;
import org.mockserver.codec.BodyDecoderEncoder;
import org.mockserver.codec.ExpandedParameterDecoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.mockserver.url.URLParser;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpUtil.isKeepAlive;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.NottableString.strings;

/**
 * @author jamesdbloom
 */
public class FullHttpRequestToMockServerHttpRequest {

    // DELIBERATE REGRESSION (item 16 PR control) — a static sink so the probe
    // allocation above genuinely escapes and cannot be scalar-replaced away by the
    // JIT, which would make the gate see nothing. MUST NEVER REACH MASTER.
    @SuppressWarnings("unused")
    private static volatile byte[] allocationGateProbe;

    private final MockServerLogger mockServerLogger;
    private final BodyDecoderEncoder bodyDecoderEncoder;
    private final ExpandedParameterDecoder formParameterParser;
    private final boolean isSecure;
    private final Certificate[] clientCertificates;
    private final Integer port;
    private final JDKCertificateToMockServerX509Certificate jdkCertificateToMockServerX509Certificate;

    public FullHttpRequestToMockServerHttpRequest(Configuration configuration, MockServerLogger mockServerLogger, boolean isSecure, Certificate[] clientCertificates, Integer port) {
        this.mockServerLogger = mockServerLogger;
        this.bodyDecoderEncoder = new BodyDecoderEncoder();
        this.formParameterParser = new ExpandedParameterDecoder(configuration, mockServerLogger);
        this.isSecure = isSecure;
        this.clientCertificates = clientCertificates;
        this.port = port;
        this.jdkCertificateToMockServerX509Certificate = new JDKCertificateToMockServerX509Certificate(mockServerLogger);
    }

    public HttpRequest mapFullHttpRequestToMockServerRequest(FullHttpRequest fullHttpRequest, List<Header> preservedHeaders, SocketAddress localAddress, SocketAddress remoteAddress, Protocol protocol) {
        return mapFullHttpRequestToMockServerRequest(fullHttpRequest, preservedHeaders, null, localAddress, remoteAddress, protocol);
    }

    public HttpRequest mapFullHttpRequestToMockServerRequest(FullHttpRequest fullHttpRequest, List<Header> preservedHeaders, byte[] originalRawBody, SocketAddress localAddress, SocketAddress remoteAddress, Protocol protocol) {
        HttpRequest httpRequest = new HttpRequest();
        // ############################################################################
        // DELIBERATE REGRESSION — performance-programme item 16, PR-path negative
        // control. This allocates ~16 KB per decoded request to prove the per-merge
        // allocation gate FAILS on a pull request and blocks it BEFORE merge, rather
        // than merely reporting a regression that already reached master.
        // THIS BRANCH MUST NEVER BE MERGED. It exists to be observed going red and
        // then closed. If you are reading this on master, something has gone wrong.
        // ############################################################################
        allocationGateProbe = new byte[16384];
        try {
            if (fullHttpRequest != null) {
                if (fullHttpRequest.decoderResult().isFailure()) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("exception decoding request " + fullHttpRequest.decoderResult().cause().getMessage())
                            .setThrowable(fullHttpRequest.decoderResult().cause())
                    );
                }
                populateHeadersAndMetadata(httpRequest, fullHttpRequest, preservedHeaders, localAddress, remoteAddress, protocol);
                setBody(httpRequest, fullHttpRequest, originalRawBody);
            }
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception decoding request{}")
                    .setArguments(fullHttpRequest)
                    .setThrowable(throwable)
            );
        }
        return httpRequest;
    }

    /**
     * Map the headers/method/path/query/cookies of a Netty HttpRequest (no body) to a MockServer HttpRequest.
     * Used by the early-response path which dispatches before HttpObjectAggregator buffers the body.
     */
    public HttpRequest mapHeadersOnlyHttpRequestToMockServerRequest(io.netty.handler.codec.http.HttpRequest nettyHttpRequest, List<Header> preservedHeaders, SocketAddress localAddress, SocketAddress remoteAddress, Protocol protocol) {
        HttpRequest httpRequest = new HttpRequest();
        try {
            if (nettyHttpRequest != null) {
                if (nettyHttpRequest.decoderResult().isFailure()) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("exception decoding request " + nettyHttpRequest.decoderResult().cause().getMessage())
                            .setThrowable(nettyHttpRequest.decoderResult().cause())
                    );
                }
                populateHeadersAndMetadata(httpRequest, nettyHttpRequest, preservedHeaders, localAddress, remoteAddress, protocol);
            }
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception decoding request headers{}")
                    .setArguments(nettyHttpRequest)
                    .setThrowable(throwable)
            );
        }
        return httpRequest;
    }

    private void populateHeadersAndMetadata(HttpRequest httpRequest, io.netty.handler.codec.http.HttpRequest nettyHttpRequest, List<Header> preservedHeaders, SocketAddress localAddress, SocketAddress remoteAddress, Protocol protocol) {
        httpRequest.withMethod(nettyHttpRequest.method().name());
        httpRequest.withKeepAlive(isKeepAlive(nettyHttpRequest));
        httpRequest.withSecure(isSecure);
        httpRequest.withProtocol(protocol == null ? Protocol.HTTP_1_1 : protocol);
        httpRequest.withPath(URLParser.returnPath(nettyHttpRequest.uri()));
        if (nettyHttpRequest.uri().contains("?")) {
            httpRequest.withQueryStringParameters(formParameterParser.retrieveQueryParameters(nettyHttpRequest.uri(), true));
        }
        setHeadersFromNettyRequest(httpRequest, nettyHttpRequest, preservedHeaders);
        setCookiesFromNettyRequest(httpRequest, nettyHttpRequest);
        setSocketAddressFromNettyRequest(httpRequest, nettyHttpRequest, localAddress, remoteAddress);
        jdkCertificateToMockServerX509Certificate.setClientCertificates(httpRequest, clientCertificates);
    }

    private void setHeadersFromNettyRequest(HttpRequest httpRequest, io.netty.handler.codec.http.HttpRequest nettyHttpRequest, List<Header> preservedHeaders) {
        boolean hasPreservedTransferEncoding = false;
        if (preservedHeaders != null) {
            for (Header preservedHeader : preservedHeaders) {
                if (TRANSFER_ENCODING.toString().equalsIgnoreCase(preservedHeader.getName().getValue())) {
                    hasPreservedTransferEncoding = true;
                    break;
                }
            }
        }
        HttpHeaders httpHeaders = nettyHttpRequest.headers();
        if (!httpHeaders.isEmpty()) {
            Headers headers = new Headers();
            for (String headerName : httpHeaders.names()) {
                if (hasPreservedTransferEncoding && headerName.equalsIgnoreCase(CONTENT_LENGTH.toString())) {
                    continue;
                }
                // Literal name and values: this is an actual incoming request, so a header genuinely
                // named "!foo" (or valued "!foo") must be recorded verbatim, not read as a negation
                // matcher. The withEntry(String, ...) overloads route through NottableString.string(name)
                // which strips a leading !/? — correct for matcher input, wrong for a real message.
                headers.withEntry(string(headerName, false), strings(httpHeaders.getAll(headerName), false));
            }
            httpRequest.withHeaders(headers);
        }
        if (preservedHeaders != null && !preservedHeaders.isEmpty()) {
            for (Header preservedHeader : preservedHeaders) {
                // only re-add a preserved header if it was actually removed downstream (i.e. it is no
                // longer present in the live request headers); otherwise it would be duplicated — e.g.
                // when request decompression is disabled the Content-Encoding header is never stripped
                if (!httpHeaders.contains(preservedHeader.getName().getValue())) {
                    httpRequest.withHeader(preservedHeader);
                }
            }
        }
        // The HTTP/2 stream id is carried on the x-http2-stream-id extension header that
        // InboundHttp2ToHttpAdapter sets on every converted HTTP/2 request. Capture it ONLY when the
        // request genuinely arrived over HTTP/2 (protocol == HTTP_2). This guards against a plain
        // HTTP/1.1 client forging an x-http2-stream-id header to contaminate the request model / logs /
        // HAR export. The HTTP/2 protocol value is a server-side trusted signal: for TLS-negotiated h2
        // it comes from ALPN, and for cleartext h2c the pipeline sets the negotiated protocol to HTTP_2
        // when it detects the h2c connection preface (PortUnificationHandler.switchToH2c) — neither is
        // derived from a client-supplied header, so it cannot be spoofed over an HTTP/1.1 connection.
        if (Protocol.HTTP_2.equals(httpRequest.getProtocol())) {
            Integer streamId = nettyHttpRequest.headers().getInt(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
            if (streamId != null) {
                httpRequest.withStreamId(streamId);
            }
        }
    }

    private void setCookiesFromNettyRequest(HttpRequest httpRequest, io.netty.handler.codec.http.HttpRequest nettyHttpRequest) {
        List<String> cookieHeaders = nettyHttpRequest.headers().getAll(COOKIE);
        if (!cookieHeaders.isEmpty()) {
            Cookies cookies = new Cookies();
            for (String cookieHeader : cookieHeaders) {
                Set<Cookie> decodedCookies = ServerCookieDecoder.LAX.decode(cookieHeader);
                for (io.netty.handler.codec.http.cookie.Cookie decodedCookie : decodedCookies) {
                    // literal name and value — a real cookie, not a matcher (see the header note above)
                    cookies.withEntry(
                        string(decodedCookie.name(), false),
                        string(decodedCookie.value(), false)
                    );
                }
            }
            httpRequest.withCookies(cookies);
        }
    }

    private void setSocketAddressFromNettyRequest(HttpRequest httpRequest, io.netty.handler.codec.http.HttpRequest nettyHttpRequest, SocketAddress localAddress, SocketAddress remoteAddress) {
        httpRequest.withSocketAddress(isSecure, nettyHttpRequest.headers().get("host"), port);
        if (remoteAddress instanceof InetSocketAddress) {
            httpRequest.withRemoteAddress(Strings.CS.removeStart(remoteAddress.toString(), "/"));
        }
        if (localAddress instanceof InetSocketAddress) {
            httpRequest.withLocalAddress(Strings.CS.removeStart(localAddress.toString(), "/"));
        }
    }

    private void setBody(HttpRequest httpRequest, FullHttpRequest fullHttpRequest, byte[] originalRawBody) {
        ByteBuf content = fullHttpRequest.content();
        byte[] decompressedBytes = null;
        if (content != null && content.readableBytes() > 0) {
            decompressedBytes = new byte[content.readableBytes()];
            // non-destructive read (does not advance the reader index) so the body is
            // materialised exactly once: these bytes are handed straight to bytesToBody
            // below instead of letting byteBufToBody allocate and read a second identical
            // copy out of the same ByteBuf. The mapper neither owns nor releases content
            // (the FullHttpRequest is released by the inbound MessageToMessageDecoder), so
            // leaving the reader index untouched is safe and nothing downstream re-reads it.
            content.getBytes(content.readerIndex(), decompressedBytes);
        }
        // bytesToBody on the already-materialised bytes is byte-for-byte equivalent to the
        // previous byteBufToBody(content) call (which itself only copied content into a
        // byte[] then delegated to bytesToBody) — and preserves the null body when there
        // are no readable bytes (byteBufToBody also returned null for empty/null content).
        if (decompressedBytes != null) {
            httpRequest.withBody(bodyDecoderEncoder.bytesToBody(decompressedBytes, fullHttpRequest.headers().get(CONTENT_TYPE)));
        }
        // retain the original on-the-wire bytes only when the body was actually compressed (i.e. the
        // captured bytes differ from the decompressed body), so getBodyAsOriginalRawBytes() returns what
        // the client sent and a BinaryBody expectation can match the compressed payload
        if (originalRawBody != null && originalRawBody.length > 0 && !Arrays.equals(originalRawBody, decompressedBytes)) {
            httpRequest.withOriginalBody(originalRawBody);
        }
    }
}

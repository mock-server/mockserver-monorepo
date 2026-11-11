package org.mockserver.mappers;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.AsciiString;
import org.apache.commons.lang3.Strings;
import org.mockserver.codec.BodyDecoderEncoder;
import org.mockserver.codec.ExpandedParameterDecoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.ClientIpMatcher;
import org.mockserver.model.*;
import org.mockserver.url.URLParser;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpUtil.isKeepAlive;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.NottableString.headerName;

/**
 * @author jamesdbloom
 */
public class FullHttpRequestToMockServerHttpRequest {

    private static final int MAX_SHARED_HEADERS = 64;
    private static final BooleanSupplier CLIENT_IP_IN_USE = ClientIpMatcher::isInUse;
    private final MockServerLogger mockServerLogger;
    private final BodyDecoderEncoder bodyDecoderEncoder;
    private final ExpandedParameterDecoder formParameterParser;
    private final boolean isSecure;
    private final Certificate[] clientCertificates;
    private final Integer port;
    private final JDKCertificateToMockServerX509Certificate jdkCertificateToMockServerX509Certificate;
    private SocketAddress cachedRemoteAddress;
    private String cachedRemoteAddressString;
    private HttpRequest.RemoteAddress cachedRemoteAddressReceived;
    private BooleanSupplier clientIpInUse = CLIENT_IP_IN_USE;
    private SocketAddress cachedLocalAddress;
    private String cachedLocalAddressString;
    private final HeaderMemo headerNames = new HeaderMemo(true);
    private final HeaderMemo headerValues = new HeaderMemo(false);

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

    /**
     * Maps a request as decoded, without checking its decoder result: a request the HTTP/1.1 codec could not decode
     * is refused before it reaches a mapper (see {@code HttpChunkLineLimiter}).
     */
    public HttpRequest mapFullHttpRequestToMockServerRequest(FullHttpRequest fullHttpRequest, List<Header> preservedHeaders, byte[] originalRawBody, SocketAddress localAddress, SocketAddress remoteAddress, Protocol protocol) {
        HttpRequest httpRequest = new HttpRequest();
        try {
            if (fullHttpRequest != null) {
                populateHeadersAndMetadata(httpRequest, fullHttpRequest, preservedHeaders, localAddress, remoteAddress, protocol);
                setBody(httpRequest, fullHttpRequest, originalRawBody);
            }
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setHttpRequestIfPresent(NettyMessageForLog.request(fullHttpRequest))
                    .setMessageFormat("exception decoding request{}")
                    .setArguments(NettyMessageForLog.request(fullHttpRequest))
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
                populateHeadersAndMetadata(httpRequest, nettyHttpRequest, preservedHeaders, localAddress, remoteAddress, protocol);
            }
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setHttpRequestIfPresent(NettyMessageForLog.request(nettyHttpRequest))
                    .setMessageFormat("exception decoding request headers{}")
                    .setArguments(NettyMessageForLog.request(nettyHttpRequest))
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
        if (URLParser.isFullUrl(nettyHttpRequest.uri())) {
            httpRequest.withAbsoluteFormRequestTarget(true);
        }
        if (nettyHttpRequest.uri().contains("?")) {
            formParameterParser.applyQueryParameters(httpRequest, nettyHttpRequest.uri(), true);
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
            // One pass: iteratorCharSequence yields netty's HeaderEntry itself, so there is no names()
            // LinkedHashSet and no getAll() re-walk per name. Presized from the O(1) header count.
            // Literal name and value - a real request may genuinely carry a header named or valued
            // "!foo", which the withEntry(String, ...) overloads would parse as a negation matcher.
            // The store now holds wire order; every wire and JSON view regroups by first-occurrence
            // key through getEntries(), so nothing observable changes.
            headers.reserve(httpHeaders.size());
            Iterator<Map.Entry<CharSequence, CharSequence>> headerIterator = httpHeaders.iteratorCharSequence();
            headerNames.ensureCapacity(httpHeaders.size());
            headerValues.ensureCapacity(httpHeaders.size());
            // over HTTP/1.1 the only repeated AsciiString instances are decoder constants, already shared
            boolean overHttp2 = Protocol.HTTP_2.equals(httpRequest.getProtocol());
            int position = 0;
            while (headerIterator.hasNext()) {
                Map.Entry<CharSequence, CharSequence> header = headerIterator.next();
                // over HTTP/1.1 an x-http2- header is the client's own; the stream id is kept in streamId below
                if (overHttp2 && Http2ExtensionHeaders.isExtensionHeader(header.getKey())) {
                    continue;
                }
                NottableString name = headerNames.share(position, header.getKey(), overHttp2);
                NottableString value = headerValues.share(position, header.getValue(), overHttp2);
                position++;
                if (hasPreservedTransferEncoding && name.getValue().equalsIgnoreCase(CONTENT_LENGTH.toString())) {
                    continue;
                }
                headers.appendLiteral(name, value);
            }
            headerNames.finishRequest(position);
            headerValues.finishRequest(position);
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

    /**
     * The previous request's header name (or value) wrappers on this connection, by position: an equal
     * (case-sensitive) name or value reuses the earlier immutable wrapper, so the event log retains one copy
     * per connection instead of one per request. Double-buffered ({@code previous*} is read while
     * {@code current*} is filled, then they swap), holding one request's first {@link #MAX_SHARED_HEADERS}
     * headers at most. Arrays are allocated on first use: a mapper that maps one request allocates half.
     */
    private static final class HeaderMemo {
        private static final NottableString[] NO_WRAPPERS = new NottableString[0];
        private static final AsciiString[] NO_RECEIVED = new AsciiString[0];
        private final boolean names;
        private NottableString[] previous = NO_WRAPPERS;
        private AsciiString[] previousReceived = NO_RECEIVED;
        private NottableString[] current = NO_WRAPPERS;
        private AsciiString[] currentReceived = NO_RECEIVED;

        private HeaderMemo(boolean names) {
            this.names = names;
        }

        private void ensureCapacity(int headerCount) {
            int length = Math.min(headerCount, MAX_SHARED_HEADERS);
            if (current.length < length) {
                // finishRequest emptied current, so it is replaced rather than copied
                current = new NottableString[length];
                currentReceived = new AsciiString[length];
            }
        }

        private NottableString share(int position, CharSequence text, boolean findReordered) {
            if (position >= current.length) {
                return wrap(text);
            }
            AsciiString asciiText = text instanceof AsciiString ? (AsciiString) text : null;
            NottableString wrapped = position < previous.length ? previous[position] : null;
            // HTTP/2 names and values are AsciiString, usually the same HPACK table instance on every
            // request; AsciiString.equals short-circuits on identity and compares bytes in bulk, not chars.
            if (wrapped != null) {
                AsciiString received = previousReceived[position];
                if (!(received != null && asciiText != null ? received.equals(asciiText) : wrapped.getValue().contentEquals(text))) {
                    wrapped = null;
                }
            }
            if (wrapped == null) {
                wrapped = findReordered && asciiText != null ? sameInstanceAtAnyPosition(asciiText) : null;
                if (wrapped == null) {
                    wrapped = wrap(text);
                }
            }
            current[position] = wrapped;
            currentReceived[position] = asciiText;
            return wrapped;
        }

        // Some HTTP/2 clients (Go's, and so gRPC-Go and k6) order their headers differently on every
        // request, but an HPACK-indexed header is still the same instance, so it is found by identity.
        // previous is filled contiguously, so its first null ends the previous request's headers.
        private NottableString sameInstanceAtAnyPosition(AsciiString text) {
            for (int i = 0; i < previous.length && previous[i] != null; i++) {
                if (previousReceived[i] == text) {
                    return previous[i];
                }
            }
            return null;
        }

        private NottableString wrap(CharSequence text) {
            return names ? headerName(text.toString()) : string(text.toString(), false);
        }

        private void finishRequest(int headerCount) {
            clearFrom(current, currentReceived, headerCount);
            NottableString[] wrappers = previous;
            previous = current;
            current = wrappers;
            AsciiString[] received = previousReceived;
            previousReceived = currentReceived;
            currentReceived = received;
            // the buffer swapped out still holds the request before last; drop it so only one is retained
            clearFrom(current, currentReceived, 0);
        }

        private static void clearFrom(NottableString[] wrappers, AsciiString[] received, int position) {
            for (int i = position; i < wrappers.length && wrappers[i] != null; i++) {
                wrappers[i] = null;
                received[i] = null;
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
            setRemoteAddress(httpRequest, remoteAddress);
        }
        if (localAddress instanceof InetSocketAddress) {
            httpRequest.withLocalAddress(localAddressString(localAddress));
        }
    }

    // netty memoises both addresses on the channel, so the instance is stable for the life of the
    // connection and its identity is a safe cache key. The mapper is per pipeline, or per HTTP/2
    // connection (shared by its stream child channels, which all run on the connection's event loop),
    // so it is only touched from one event-loop thread and no synchronisation is needed.
    // Until any expectation uses clientIp a request gets the cached text, exactly as before; after that, a holder
    // built once per connection that also keeps the peer's InetAddress, so a clientIp comparison never parses.
    private void setRemoteAddress(HttpRequest httpRequest, SocketAddress remoteAddress) {
        if (remoteAddress != cachedRemoteAddress) {
            cachedRemoteAddress = remoteAddress;
            cachedRemoteAddressString = Strings.CS.removeStart(remoteAddress.toString(), "/");
            cachedRemoteAddressReceived = null;
        }
        if (cachedRemoteAddressReceived == null && clientIpInUse.getAsBoolean()) {
            cachedRemoteAddressReceived = new HttpRequest.RemoteAddress(cachedRemoteAddressString, ((InetSocketAddress) remoteAddress).getAddress());
        }
        if (cachedRemoteAddressReceived != null) {
            httpRequest.withReceivedRemoteAddress(cachedRemoteAddressReceived);
        } else {
            httpRequest.withRemoteAddress(cachedRemoteAddressString);
        }
    }

    void clientIpInUseForTesting(BooleanSupplier clientIpInUse) {
        this.clientIpInUse = clientIpInUse;
    }

    private String localAddressString(SocketAddress localAddress) {
        if (localAddress != cachedLocalAddress) {
            cachedLocalAddress = localAddress;
            cachedLocalAddressString = Strings.CS.removeStart(localAddress.toString(), "/");
        }
        return cachedLocalAddressString;
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
        // the raw body is captured only for a request with a Content-Encoding, the only kind a forward re-encodes
        if (originalRawBody != null) {
            httpRequest.markBodyAsReceived();
        }
    }
}

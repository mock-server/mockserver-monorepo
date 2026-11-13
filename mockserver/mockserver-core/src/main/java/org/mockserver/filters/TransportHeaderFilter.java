package org.mockserver.filters;

import org.mockserver.mock.Expectation;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NottableString;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which request headers describe the connection or the message framing rather than the request,
 * so must never become a required matcher when recorded traffic is turned into an expectation. A recorded
 * {@code Proxy-Connection} or {@code content-length} would make the mock match only a client that happens to
 * send the same value: the same request from another client gets a 404.
 * <p>
 * Covers the hop-by-hop headers of RFC 9110 section 7.6.1 (including any header the request's own
 * {@code Connection} header nominates), every {@code Proxy-*} header, {@code Content-Length}, which the
 * body already determines, {@code x-mockserver-forwarded-by}, the forward-loop count whose values identify one
 * run of a MockServer instance, and {@code x-forwarded-by}, which names the proxies a request passed through (a
 * MockServer adds it to a proxied request whose path a control plane serves). It also covers gRPC's
 * {@code grpc-timeout}, the time left before the call's deadline, and {@code grpc-previous-rpc-attempts}, the retry
 * count, which differ from one call to the next. {@code Host} is kept by default: it
 * is what tells recordings of two upstreams apart. Promoting recordings to active mocks also drops it, so an application calling MockServer directly
 * (with its own {@code Host}) matches the promoted mock.
 */
public final class TransportHeaderFilter {

    private static final Set<String> TRANSPORT_HEADERS = Set.of(
        "connection",
        "keep-alive",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "content-length",
        "x-mockserver-forwarded-by",
        "x-forwarded-by",
        "grpc-timeout",
        "grpc-previous-rpc-attempts"
    );

    private TransportHeaderFilter() {
    }

    /**
     * @param name a header name in any case (may be {@code null})
     * @return true when the header is hop-by-hop or transport-level, judged by its name alone
     */
    public static boolean isTransportHeader(String name) {
        if (name == null) {
            return false;
        }
        String lowerCaseName = name.toLowerCase(Locale.ROOT);
        return lowerCaseName.startsWith("proxy-") || TRANSPORT_HEADERS.contains(lowerCaseName);
    }

    /**
     * The request without its hop-by-hop and transport headers, keeping {@code Host}. The argument is never
     * modified: a request carrying none of them is returned as it is, otherwise a stripped clone is returned.
     */
    public static HttpRequest withoutTransportHeaders(HttpRequest request) {
        return withoutTransportHeaders(request, false);
    }

    /**
     * As {@link #withoutTransportHeaders(HttpRequest)}, also dropping {@code Host} when {@code stripHost} is true.
     */
    public static HttpRequest withoutTransportHeaders(HttpRequest request, boolean stripHost) {
        if (request == null || request.getHeaders() == null || request.getHeaders().isEmpty()) {
            return request;
        }
        Set<String> nominated = nominatedByConnectionHeader(request);
        Set<NottableString> toRemove = new HashSet<>();
        for (Header header : request.getHeaderList()) {
            String name = header.getName() != null ? header.getName().getValue() : null;
            if (isTransportHeader(name)
                || (stripHost && "host".equalsIgnoreCase(name))
                || (name != null && nominated.contains(name.toLowerCase(Locale.ROOT)))) {
                toRemove.add(header.getName());
            }
        }
        if (toRemove.isEmpty()) {
            return request;
        }
        HttpRequest stripped = request.clone();
        for (NottableString name : toRemove) {
            stripped.removeHeader(name);
        }
        if (stripped.getHeaders() != null && stripped.getHeaders().isEmpty()) {
            stripped.withHeaders((Headers) null);
        }
        return stripped;
    }

    /**
     * The expectation with its request matcher stripped of hop-by-hop and transport headers, keeping
     * {@code Host}. The argument is never modified: when nothing needs stripping it is returned as it is,
     * otherwise a copy (same id) is.
     */
    public static Expectation withoutTransportHeaders(Expectation expectation) {
        return withoutTransportHeaders(expectation, false);
    }

    /**
     * As {@link #withoutTransportHeaders(Expectation)}, also dropping {@code Host} when {@code stripHost} is true.
     */
    public static Expectation withoutTransportHeaders(Expectation expectation, boolean stripHost) {
        if (expectation == null || !(expectation.getHttpRequest() instanceof HttpRequest)) {
            return expectation;
        }
        HttpRequest request = (HttpRequest) expectation.getHttpRequest();
        HttpRequest stripped = withoutTransportHeaders(request, stripHost);
        if (stripped == request) {
            return expectation;
        }
        return expectation.cloneWith(stripped, expectation.getHttpResponse());
    }

    private static Set<String> nominatedByConnectionHeader(HttpRequest request) {
        Set<String> nominated = new HashSet<>();
        for (String value : request.getHeader("connection")) {
            if (value != null) {
                for (String token : value.split(",")) {
                    String trimmed = token.trim().toLowerCase(Locale.ROOT);
                    if (!trimmed.isEmpty()) {
                        nominated.add(trimmed);
                    }
                }
            }
        }
        return nominated;
    }
}

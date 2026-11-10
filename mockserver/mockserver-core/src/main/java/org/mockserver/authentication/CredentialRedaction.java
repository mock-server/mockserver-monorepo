package org.mockserver.authentication;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.mappers.NettyMessageForLog;
import org.mockserver.mock.HttpState;
import org.mockserver.model.RequestDefinition;
import org.mockserver.model.Cookies;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A copy of a control-plane request that is safe to attach to a log entry: every credential
 * header ({@code Authorization}, {@code Proxy-Authorization}, {@code Cookie}, API-key headers) is replaced by the
 * redaction mask and cookies are dropped. Such an entry is written whether or not {@code redactSecretsInLog} is on,
 * and a failed or throttled attempt can carry a near miss of, or the correct, password.
 * Proxy requests keep origin credentials; only {@code Proxy-Authorization} is removed.
 */
public final class CredentialRedaction {

    static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "x-api-key", "api-key");

    private CredentialRedaction() {
    }

    /**
     * The request a log entry may hold: without credentials when it is a control-plane request, whether the
     * control-plane gate marked it or its method and path name a control-plane route, so no log site can store
     * one unmasked. A request marked as proxy traffic skips the route check, since an upstream may use the
     * same path. Proxy requests lose only {@code Proxy-Authorization}; origin credentials are kept. Requests
     * without proxy credentials are returned unchanged.
     */
    public static RequestDefinition forLog(RequestDefinition requestDefinition) {
        try {
            // the forward client's copy carrying forwardCredentialHeadersByHost credentials is logged as it was before
            if (requestDefinition instanceof HttpRequest && ((HttpRequest) requestDefinition).getRequestBeforeForwardCredentials() != null) {
                requestDefinition = ((HttpRequest) requestDefinition).getRequestBeforeForwardCredentials();
            }
            if (requestDefinition instanceof HttpRequest) {
                HttpRequest request = (HttpRequest) requestDefinition;
                if (isControlPlane(request)) {
                    return withoutCredentials(request);
                }
                if (request.containsHeader("Proxy-Authorization")) {
                    return withoutProxyCredentials(request);
                }
            }
        } catch (RuntimeException unreadable) {
            // a log entry must not fail the code reporting a failure, often one about this very request, nor keep
            // credentials it could not check: fail closed to a stand-in that carries no headers at all
            return unreadableStandIn(requestDefinition);
        }
        return requestDefinition;
    }

    private static HttpRequest withoutProxyCredentials(HttpRequest request) {
        HttpRequest copy = request.clone().removeHeader("Proxy-Authorization");
        copy.withLogCorrelationId(request.getLogCorrelationId());
        return copy;
    }

    private static HttpRequest unreadableStandIn(RequestDefinition unreadable) {
        HttpRequest standIn = HttpRequest.request();
        try {
            if (unreadable instanceof HttpRequest && ((HttpRequest) unreadable).getPath() != null) {
                standIn.withPath(((HttpRequest) unreadable).getPath().getValue());
            }
            standIn.withLogCorrelationId(unreadable.getLogCorrelationId());
        } catch (RuntimeException alsoUnreadable) {
            // the stand-in is still credential-free without them
        }
        return standIn;
    }

    /**
     * A log argument: as {@link #forLog(RequestDefinition)} for a request, and a Netty request to a control-plane
     * route (whose {@code toString()} lists every header) replaced by a masked copy.
     */
    public static Object argumentForLog(Object argument) {
        if (argument instanceof HttpRequest) {
            return forLog((HttpRequest) argument);
        }
        if (argument instanceof io.netty.handler.codec.http.HttpRequest) {
            io.netty.handler.codec.http.HttpRequest nettyRequest = (io.netty.handler.codec.http.HttpRequest) argument;
            try {
                if (HttpState.isControlPlaneRequestTarget(nettyRequest.method() != null ? nettyRequest.method().name() : null, nettyRequest.uri())) {
                    HttpRequest mapped = NettyMessageForLog.request(nettyRequest);
                    return mapped != null ? withoutCredentials(mapped) : "(control plane request, not shown as it may carry credentials)";
                }
                if (nettyRequest.headers().contains("Proxy-Authorization")) {
                    HttpRequest mapped = NettyMessageForLog.proxyRequest(nettyRequest);
                    return mapped != null ? mapped : "(proxy request, not shown as it may carry credentials)";
                }
            } catch (RuntimeException unreadable) {
                return "(request, not shown as it could not be read)";
            }
        }
        return argument;
    }

    private static boolean isControlPlane(HttpRequest request) {
        if (request.isControlPlaneRequest()) {
            return true;
        }
        if (request.isDataPlaneRequest() || request.getPath() == null) {
            return false;
        }
        return HttpState.isControlPlaneRoute(request.getMethod() != null ? request.getMethod().getValue() : null, request.getPath().getValue());
    }


    /**
     * @return a copy of {@code request} without credentials, or {@code request} itself when it carries none
     */
    public static HttpRequest withoutCredentials(HttpRequest request) {
        if (request == null || !hasCredentials(request)) {
            return request;
        }
        HttpRequest copy = request.clone();
        List<String> masked = new ArrayList<>();
        if (request.getMaskedCredentialValues() != null) {
            masked.addAll(request.getMaskedCredentialValues());
        }
        for (Header header : request.getHeaderList()) {
            String name = header.getName().getValue();
            if (CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                header.getValues().forEach(value -> masked.add(value.getValue()));
                copy.replaceHeader(new Header(name, ConfigurationProperties.REDACTED_VALUE));
            }
        }
        if (request.getCookieList() != null) {
            request.getCookieList().forEach(cookie -> masked.add(cookie.getValue().getValue()));
        }
        masked.removeIf(ConfigurationProperties.REDACTED_VALUE::equals);
        copy.withLogCorrelationId(request.getLogCorrelationId());
        return copy.withCookies((Cookies) null).withMaskedCredentialValues(masked);
    }

    private static boolean hasCredentials(HttpRequest request) {
        if (request.getCookieList() != null && !request.getCookieList().isEmpty()) {
            return true;
        }
        for (Header header : request.getHeaderList()) {
            if (CREDENTIAL_HEADERS.contains(header.getName().getValue().toLowerCase(Locale.ROOT))) {
                for (org.mockserver.model.NottableString value : header.getValues()) {
                    if (!ConfigurationProperties.REDACTED_VALUE.equals(value.getValue())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}

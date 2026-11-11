package org.mockserver.authentication;

import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;

import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;

/**
 * Decides whether a request a client sent to MockServer as its HTTP proxy must be refused for want of proxy credentials
 * before anything else happens to it, matching against expectations included.
 *
 * <p>Such a request has a request-target that is not origin-form ({@link HttpRequest#isAbsoluteFormRequestTarget()}), as
 * RFC 9112 3.2.2 has a client send to a proxy. Its destination is its {@code Host}, as for forwarding: one that is one
 * of MockServer's own addresses is addressed to MockServer and needs no credentials. An origin-form request is addressed
 * to MockServer whatever its {@code Host} says, so it is matched without proxy credentials, and is asked for them only
 * when MockServer would forward it.
 */
public final class AbsoluteFormProxyAuthentication {

    private AbsoluteFormProxyAuthentication() {
    }

    /**
     * Whether the request needs proxy credentials. It does not read them: the caller decides with the result of
     * {@link ProxyAuthenticationValidator#authenticateAndRemoveCredentials}, which has already removed them.
     *
     * @param localAddresses  the {@code host[:port]} forms of the address the request arrived on
     * @param proxyingRequest whether the connection is a tunnel, whose credentials were checked when it was opened, or a
     *                        transparent-proxy or port-forwarding connection, which is never asked for them
     * @return {@code true} when proxy credentials are configured and the request is for another host
     */
    public static boolean requiresCredentials(Configuration configuration, HttpRequest request, Set<String> localAddresses, boolean proxyingRequest) {
        if (!request.isAbsoluteFormRequestTarget() || proxyingRequest) {
            return false;
        }
        if (!ProxyAuthenticationValidator.proxyAuthenticationConfigured(configuration.proxyAuthenticationUsername(), configuration.proxyAuthenticationPassword())) {
            return false;
        }
        String host = request.getFirstHeader(HOST.toString());
        return localAddresses == null || !localAddresses.contains(host);
    }
}

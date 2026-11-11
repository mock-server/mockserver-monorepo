package org.mockserver.authentication;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.HttpRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * The single validation point for HTTP forward-proxy {@code Proxy-Authorization} credentials, shared by
 * the {@code CONNECT} path and the plain HTTP proxy path.
 *
 * <p><strong>Why this exists.</strong> Both paths previously checked the credential with
 * {@code request.containsHeader(PROXY_AUTHORIZATION, "Basic " + base64)}, which routes into
 * {@code KeysToMultiValues.containsEntry} and compares with {@link String#equalsIgnoreCase}. That is wrong
 * twice over for a credential:
 * <ul>
 *   <li>Base64 is case-SENSITIVE, so a case-insensitive comparison accepts tokens that differ from the
 *       configured one in case — roughly one bit of entropy lost per alphabetic character in the
 *       credential.</li>
 *   <li>{@code equalsIgnoreCase} short-circuits on the first differing character, reintroducing exactly the
 *       timing side channel {@link ConstantTimeEquals} exists to close.</li>
 * </ul>
 *
 * <p>Routing both call sites through here means there is one audited comparison, and
 * {@code ProxyAuthenticationValidatorTest} can assert that a case-mutated credential is REJECTED — a guard
 * that fails if either call site ever drifts back off the constant-time path.
 */
public final class ProxyAuthenticationValidator {

    private static final String BASIC_SCHEME = "Basic";

    private ProxyAuthenticationValidator() {
    }

    /**
     * @return {@code true} when proxy authentication is configured (both username and password non-blank)
     * and therefore must be enforced
     */
    public static boolean proxyAuthenticationConfigured(String username, String password) {
        return isNotBlank(username) && isNotBlank(password);
    }

    /**
     * The {@code Proxy-Authorization} header value the configured credentials require.
     */
    public static String expectedProxyAuthorizationHeaderValue(String username, String password) {
        // java.util.Base64.getEncoder() is byte-for-byte equivalent to Netty's
        // Base64.encode(buffer, false) here: standard alphabet, padded, no line wrapping. Using it
        // avoids allocating two unpooled Netty ByteBufs (the copiedBuffer input and the encoded
        // output) that were never released on every proxy-authenticated request.
        return BASIC_SCHEME + ' ' + encodeCredentials(username, password);
    }

    private static String encodeCredentials(String username, String password) {
        return Base64.getEncoder().encodeToString((username + ':' + password).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validate the request's {@code Proxy-Authorization} header against the configured credentials.
     *
     * <p>The {@code Basic} scheme name matches case-insensitively (ASCII only) and may be followed by one or
     * more spaces (RFC 7235 / RFC 7617). The encoded token comparison is EXACT (case-sensitive, as base64
     * requires) and constant-time. Every candidate header value is compared without short-circuiting on the
     * result, so the number of comparisons does not depend on which value matched.
     *
     * @return {@code true} if the request carries a valid credential, or if proxy authentication is not
     * configured (nothing to enforce)
     */
    public static boolean isAuthenticated(HttpRequest request, String username, String password) {
        if (!proxyAuthenticationConfigured(username, password)) {
            return true;
        }
        if (request == null) {
            return false;
        }
        String expected = encodeCredentials(username, password);
        List<String> suppliedValues = request.getHeader(PROXY_AUTHORIZATION.toString());
        if (suppliedValues == null) {
            return false;
        }
        boolean authenticated = false;
        for (String suppliedValue : suppliedValues) {
            // deliberately no short-circuit: |= rather than an early return
            authenticated |= ConstantTimeEquals.equals(basicCredentialToken(suppliedValue), expected);
        }
        return authenticated;
    }

    /**
     * As {@link #isAuthenticated}, and then, when proxy authentication is configured, removes every
     * {@code Proxy-Authorization} header from {@code request}: that header is the client's credential for the proxy
     * hop, so it must not be matched, logged, recorded or forwarded. Without proxy authentication configured the
     * request is left exactly as it is, and nothing is allocated.
     *
     * @return {@code true} if the request carried a valid credential, or if proxy authentication is not configured
     */
    public static boolean authenticateAndRemoveCredentials(HttpRequest request, String username, String password) {
        if (!proxyAuthenticationConfigured(username, password)) {
            return true;
        }
        boolean authenticated = isAuthenticated(request, username, password);
        if (request != null && request.containsHeader(PROXY_AUTHORIZATION.toString())) {
            request.removeHeader(PROXY_AUTHORIZATION.toString());
        }
        return authenticated;
    }

    /**
     * For a log entry made before {@link #authenticateAndRemoveCredentials} has run, such as a data-plane
     * authentication refusal: a clone of {@code request} without {@code Proxy-Authorization} when proxy
     * authentication is configured and the request carries one, otherwise {@code request} itself.
     */
    public static HttpRequest withoutProxyCredential(HttpRequest request, String username, String password) {
        if (request == null || !proxyAuthenticationConfigured(username, password) || !request.containsHeader(PROXY_AUTHORIZATION.toString())) {
            return request;
        }
        HttpRequest copy = request.clone().removeHeader(PROXY_AUTHORIZATION.toString());
        copy.withLogCorrelationId(request.getLogCorrelationId());
        return copy;
    }

    /**
     * As {@link #withoutProxyCredential(HttpRequest, String, String)}, for a log site that has only its logger's
     * {@code configuration}: without one, the credentials come from {@link ConfigurationProperties}, as the logger's do.
     */
    public static HttpRequest withoutProxyCredential(HttpRequest request, Configuration configuration) {
        return configuration != null
            ? withoutProxyCredential(request, configuration.proxyAuthenticationUsername(), configuration.proxyAuthenticationPassword())
            : withoutProxyCredential(request, ConfigurationProperties.proxyAuthenticationUsername(), ConfigurationProperties.proxyAuthenticationPassword());
    }

    /**
     * @return the token after a {@code Basic} scheme and its separating spaces, or {@code null} when the value
     * does not start with that scheme, has no space after it, or has no token
     */
    private static String basicCredentialToken(String suppliedValue) {
        int schemeLength = BASIC_SCHEME.length();
        if (suppliedValue == null || suppliedValue.length() <= schemeLength || suppliedValue.charAt(schemeLength) != ' ') {
            return null;
        }
        for (int i = 0; i < schemeLength; i++) {
            if (asciiLowerCase(suppliedValue.charAt(i)) != asciiLowerCase(BASIC_SCHEME.charAt(i))) {
                return null;
            }
        }
        int tokenStart = schemeLength;
        while (tokenStart < suppliedValue.length() && suppliedValue.charAt(tokenStart) == ' ') {
            tokenStart++;
        }
        return tokenStart < suppliedValue.length() ? suppliedValue.substring(tokenStart) : null;
    }

    // deliberately not regionMatches(true, ...) or Character case mapping: they fold non-ASCII lookalikes such as
    // U+0130, U+0131 and U+017F onto i and s
    private static char asciiLowerCase(char character) {
        return character >= 'A' && character <= 'Z' ? (char) (character + ('a' - 'A')) : character;
    }
}

package org.mockserver.authentication;

import io.netty.util.AsciiString;
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

    private static final String BASIC_PREFIX = "Basic ";

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
        return BASIC_PREFIX + encodeCredentials(username, password);
    }

    private static String encodeCredentials(String username, String password) {
        // java.util.Base64.getEncoder() is byte-for-byte equivalent to Netty's
        // Base64.encode(buffer, false) here: standard alphabet, padded, no line wrapping. Using it
        // avoids allocating two unpooled Netty ByteBufs (the copiedBuffer input and the encoded
        // output) that were never released on every proxy-authenticated request.
        return Base64.getEncoder().encodeToString((username + ':' + password).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validate the request's {@code Proxy-Authorization} header against the configured credentials.
     *
     * <p>The encoded token comparison is EXACT (case-sensitive, as base64 requires) and constant-time.
     * Every candidate header value is compared without short-circuiting on the result, so the number of comparisons does
     * not depend on which value matched.
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
            String token = suppliedValue != null && suppliedValue.length() > BASIC_PREFIX.length()
                && AsciiString.contentEqualsIgnoreCase(BASIC_PREFIX, suppliedValue.substring(0, BASIC_PREFIX.length()))
                ? suppliedValue.substring(BASIC_PREFIX.length()) : null;
            // deliberately no short-circuit: |= rather than an early return
            authenticated |= ConstantTimeEquals.equals(token, expected);
        }
        return authenticated;
    }
}

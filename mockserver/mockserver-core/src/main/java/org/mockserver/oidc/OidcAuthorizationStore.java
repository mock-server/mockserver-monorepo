package org.mockserver.oidc;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

/**
 * In-memory state backing the mock OIDC authorization-code flow.
 *
 * <p>Two pieces of state are held:
 * <ul>
 *   <li><b>Providers</b> — one {@link Provider} per generated OIDC provider, keyed by the
 *       {@code authorizePath}/{@code tokenPath} it serves. Each provider carries its configuration
 *       and the {@link OidcTokenMinter} (with its signing key pair) so tokens are minted per request,
 *       not pre-baked. The {@code /authorize} callback looks up its provider by the request path to
 *       bind the per-request context (redirect_uri, PKCE challenge, scope, nonce) to a newly issued
 *       authorization code.</li>
 *   <li><b>Codes</b> — one {@link AuthorizationCode} per issued authorization code, keyed by the
 *       opaque code string. The {@code /token} callback consumes the code to validate the
 *       {@code authorization_code} grant (redirect_uri match + optional PKCE), then mints the token
 *       response at request time. Codes are single-use and expire after {@link #CODE_TTL_MILLIS}.</li>
 * </ul>
 *
 * <p>This is a process-wide singleton (mirroring {@code GrpcHealthRegistry} and the other in-memory
 * registries) because the {@code /authorize} and {@code /token} class callbacks are instantiated
 * fresh per request and therefore cannot share instance state.
 */
public class OidcAuthorizationStore {

    /**
     * Lifetime of an unredeemed authorization code, in milliseconds. Matches real authorization
     * servers, where codes are short-lived (RFC 6749 §4.1.2 recommends ≤ 10 minutes; 60s is a typical
     * issuer default). A code older than this is treated as expired/not-found at {@code /token} and is
     * evicted, so the codes map cannot grow unbounded with never-redeemed codes.
     */
    static final long CODE_TTL_MILLIS = 60_000L;

    /**
     * Lifetime of an unredeemed device code, in milliseconds. RFC 8628 §3.2 reports the same lifetime
     * to the client via the {@code expires_in} field of the device-authorization response. A device
     * code older than this is treated as expired at {@code /token} (RFC 8628 §3.5 {@code expired_token})
     * and evicted, so the device-codes map cannot grow unbounded with never-redeemed codes.
     */
    static final long DEVICE_CODE_TTL_MILLIS = 300_000L;

    /** Polling interval (seconds) advertised in the device-authorization response (RFC 8628 §3.2). */
    static final int DEVICE_CODE_INTERVAL_SECONDS = 5;

    /**
     * Absolute ceiling on the number of outstanding entries held in any one of the code / device-code /
     * opaque-token maps. These maps are data-plane reachable (a flood of {@code /authorize},
     * {@code /device_authorization} or opaque-token-minting requests could otherwise grow them
     * without bound faster than the TTL sweep reclaims them), so a hard cap is kept as
     * defence-in-depth on top of the per-write TTL eviction. Mirrors the 10,000 bound used by
     * {@code RecoveryAttemptRegistry} / {@code DnsIntentRegistry}. When the cap is reached, surviving
     * (un-expired) entries are dropped to make room for the new write — a flooded attacker's codes
     * are unredeemable anyway, so dropping some is safe; it simply bounds heap use.
     */
    static final int MAX_OUTSTANDING = 10_000;

    private static final OidcAuthorizationStore INSTANCE = new OidcAuthorizationStore();

    private final List<Provider> providers = new CopyOnWriteArrayList<>();
    private final Map<String, AuthorizationCode> codes = new ConcurrentHashMap<>();
    private final Map<String, DeviceCode> deviceCodes = new ConcurrentHashMap<>();
    private final Map<String, OpaqueToken> opaqueTokens = new ConcurrentHashMap<>();
    /**
     * Tokens revoked via {@code /revoke} (RFC 7009). Used as a set; the value is always
     * {@link Boolean#TRUE}. Bounded by {@link #MAX_OUTSTANDING} like the other data-plane-reachable
     * maps — entries have no natural TTL (a revocation is permanent for the life of the mock), so the
     * hard cap is the only bound.
     *
     * <p><b>Scope:</b> this set is global, not per-provider, so a token revoked through any provider's
     * {@code /revoke} is treated as revoked everywhere. That is deliberate — tokens are opaque strings
     * or self-contained JWTs with no provider tag to key on, and revoking more than strictly necessary
     * fails <em>closed</em>. A test that registers several providers and expects a revocation to affect
     * only one of them will not get that behaviour; reset the store between such tests.
     */
    private final Map<String, Boolean> revokedTokens = new ConcurrentHashMap<>();

    OidcAuthorizationStore() {
    }

    long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public static OidcAuthorizationStore getInstance() {
        return INSTANCE;
    }

    /**
     * Registers (or replaces) the provider serving the given authorize/token paths. The most
     * recently registered provider for a path wins, so re-running {@code PUT /mockserver/oidc}
     * with the same paths refreshes the minted tokens.
     */
    public synchronized void registerProvider(Provider provider) {
        // remove any existing provider serving the same authorize/token/device path so the latest wins;
        // synchronized so the remove+add compound is atomic against concurrent PUT /mockserver/oidc
        // (otherwise two threads can both pass removeIf and leave duplicate stale entries).
        providers.removeIf(existing ->
            existing.authorizePath.equals(provider.authorizePath)
                || existing.tokenPath.equals(provider.tokenPath)
                || pathsEqual(existing.deviceAuthorizationPath, provider.deviceAuthorizationPath));
        providers.add(0, provider);
    }

    private static boolean pathsEqual(String a, String b) {
        return a != null && a.equals(b);
    }

    /**
     * Finds the provider serving the given authorize path, or {@code null} if none registered.
     */
    public Provider providerForAuthorizePath(String authorizePath) {
        for (Provider provider : providers) {
            if (provider.authorizePath.equals(authorizePath)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Finds the provider serving the given token path, or {@code null} if none registered.
     */
    public Provider providerForTokenPath(String tokenPath) {
        for (Provider provider : providers) {
            if (provider.tokenPath.equals(tokenPath)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Finds the provider serving the given device-authorization path, or {@code null} if none registered.
     */
    public Provider providerForDeviceAuthorizationPath(String deviceAuthorizationPath) {
        for (Provider provider : providers) {
            if (pathsEqual(provider.deviceAuthorizationPath, deviceAuthorizationPath)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * The most recently registered provider, or {@code null} if none. Used by the discovery endpoint,
     * whose path ({@code /.well-known/openid-configuration}) is fixed by the spec and therefore cannot
     * be used to disambiguate between providers; the latest registration wins, matching
     * {@link #registerProvider}.
     */
    public Provider latestProvider() {
        return providers.isEmpty() ? null : providers.get(0);
    }

    /**
     * Finds the provider serving the given userinfo path, or {@code null} if none registered.
     */
    public Provider providerForUserinfoPath(String userinfoPath) {
        for (Provider provider : providers) {
            if (provider.config.getUserinfoPath().equals(userinfoPath)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Finds the provider serving the given introspection path, or {@code null} if none registered.
     */
    public Provider providerForIntrospectPath(String introspectPath) {
        for (Provider provider : providers) {
            if (provider.config.getIntrospectPath().equals(introspectPath)) {
                return provider;
            }
        }
        return null;
    }

    public void putCode(String code, AuthorizationCode authorizationCode) {
        long now = currentTimeMillis();
        authorizationCode.issuedAtMillis = now;
        // Opportunistically evict codes that expired before this write so the map cannot grow
        // unbounded with authorization codes that are never redeemed.
        codes.values().removeIf(existing -> isExpired(existing, now));
        // Hard cap (defence-in-depth): bound the map even under a flood of fresh, never-expiring-yet
        // codes that the TTL sweep cannot reclaim.
        trimToCap(codes);
        codes.put(code, authorizationCode);
    }

    /**
     * Drops entries from a data-plane-reachable map until it is below {@link #MAX_OUTSTANDING} so the
     * immediately-following {@code put} lands at — and never above — the cap. Iterator order on a
     * {@link ConcurrentHashMap} is unspecified, so this does not guarantee oldest-first eviction — it
     * is a last-resort heap bound on top of the per-write TTL sweep; flooded entries are unredeemable,
     * so dropping any of them is safe. The compound sweep+trim+put is not atomic, so a concurrent
     * flood may transiently overshoot the cap by a bounded amount before the next write trims it back;
     * that is acceptable for a defence-in-depth bound.
     */
    private static void trimToCap(Map<String, ?> map) {
        if (map.size() < MAX_OUTSTANDING) {
            return;
        }
        java.util.Iterator<String> it = map.keySet().iterator();
        while (map.size() >= MAX_OUTSTANDING && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /**
     * Consumes (removes and returns) the authorization code, or {@code null} if unknown or expired.
     * Codes are single-use (removed on consume) and short-lived (older than {@link #CODE_TTL_MILLIS}
     * is treated as not-found), mirroring real authorization servers.
     */
    public AuthorizationCode consumeCode(String code) {
        if (code == null) {
            return null;
        }
        AuthorizationCode authorizationCode = codes.remove(code);
        if (authorizationCode == null || isExpired(authorizationCode, currentTimeMillis())) {
            return null;
        }
        return authorizationCode;
    }

    private static boolean isExpired(AuthorizationCode authorizationCode, long now) {
        return now - authorizationCode.issuedAtMillis > CODE_TTL_MILLIS;
    }

    /** Number of authorization codes currently retained (test visibility for TTL eviction). */
    int codeCount() {
        return codes.size();
    }

    // --- Device authorization grant (RFC 8628) ---

    /**
     * Records a device code issued by the device-authorization endpoint. Like authorization codes,
     * device codes are TTL-bounded ({@link #DEVICE_CODE_TTL_MILLIS}); expired entries are evicted on
     * write so the map cannot grow unbounded with codes that are never redeemed.
     */
    public void putDeviceCode(String deviceCode, DeviceCode value) {
        long now = currentTimeMillis();
        value.issuedAtMillis = now;
        deviceCodes.values().removeIf(existing -> isDeviceCodeExpired(existing, now));
        trimToCap(deviceCodes);
        deviceCodes.put(deviceCode, value);
    }

    /**
     * Looks up a device code WITHOUT consuming it (the client polls the same code repeatedly until it
     * is approved). Returns {@code null} when unknown or expired (expired entries are evicted).
     */
    public DeviceCode peekDeviceCode(String deviceCode) {
        if (deviceCode == null) {
            return null;
        }
        DeviceCode value = deviceCodes.get(deviceCode);
        if (value == null) {
            return null;
        }
        if (isDeviceCodeExpired(value, currentTimeMillis())) {
            deviceCodes.remove(deviceCode);
            return null;
        }
        return value;
    }

    /**
     * Consumes (removes and returns) a device code once it has been approved and tokens are minted, so
     * a device code is single-use for the successful exchange. Returns {@code null} when unknown or
     * expired.
     */
    public DeviceCode consumeDeviceCode(String deviceCode) {
        if (deviceCode == null) {
            return null;
        }
        DeviceCode value = deviceCodes.remove(deviceCode);
        if (value == null || isDeviceCodeExpired(value, currentTimeMillis())) {
            return null;
        }
        return value;
    }

    private static boolean isDeviceCodeExpired(DeviceCode value, long now) {
        return now - value.issuedAtMillis > DEVICE_CODE_TTL_MILLIS;
    }

    /** Number of device codes currently retained (test visibility for TTL eviction). */
    int deviceCodeCount() {
        return deviceCodes.size();
    }

    // --- Opaque access tokens ---

    /**
     * Records an opaque access token (when {@link OidcProviderConfiguration#isOpaqueAccessToken()}) so
     * the introspection endpoint can validate it. Bounded by the token's own expiry; expired entries
     * are evicted on write so the map cannot grow unbounded.
     */
    public void putOpaqueToken(String token, OpaqueToken value) {
        long now = currentTimeMillis();
        opaqueTokens.values().removeIf(existing -> existing.isExpired(now));
        trimToCap(opaqueTokens);
        opaqueTokens.put(token, value);
    }

    /**
     * Looks up an opaque access token for introspection. Returns {@code null} when unknown; an expired
     * token is returned (so introspection can report {@code active:false}) but evicted from the map.
     */
    public OpaqueToken lookupOpaqueToken(String token) {
        if (token == null) {
            return null;
        }
        OpaqueToken value = opaqueTokens.get(token);
        if (value != null && value.isExpired(currentTimeMillis())) {
            opaqueTokens.remove(token);
        }
        return value;
    }

    /** Number of opaque access tokens currently retained (test visibility). */
    int opaqueTokenCount() {
        return opaqueTokens.size();
    }

    // --- Token revocation (RFC 7009) ---

    /**
     * Records a token as revoked so {@link OidcIntrospectionCallback} reports it inactive.
     *
     * <p>Before this existed, {@code /revoke} returned 200 and did nothing, so a token introspected
     * as active immediately after being revoked. That made "my application rejects a revoked token"
     * a test that passes while proving nothing — the exact failure mode this store exists to close.
     *
     * <p>Revocation is recorded rather than the token being deleted because JWT access tokens are
     * self-contained and are not held anywhere to delete; the revocation list is the only way to
     * make a still-cryptographically-valid JWT introspect as inactive. Opaque tokens are also removed
     * outright so they stop resolving.
     */
    public void revokeToken(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        opaqueTokens.remove(token);
        // DELIBERATE TRADE-OFF, called out because this unit exists to close silent fail-opens:
        // revocations have no natural TTL, so the only bound on this map is the MAX_OUTSTANDING cap.
        // Past the cap, evicting an entry means that token stops being reported as revoked — i.e.
        // revocation fails OPEN rather than closed. The alternative (refusing new revocations at the
        // cap) fails closed on the wrong token and is no safer. It is accepted here because reaching
        // 10,000 distinct revoked tokens in a mock provider's lifetime is not a realistic test
        // scenario, and an unbounded map is a worse risk on a data-plane-reachable endpoint. If a
        // suite ever does approach the cap, reset the mock between tests.
        trimToCap(revokedTokens);
        revokedTokens.put(revocationKey(token), Boolean.TRUE);
    }

    /** Whether the token has been revoked via {@link #revokeToken(String)}. */
    public boolean isRevoked(String token) {
        return token != null && revokedTokens.containsKey(revocationKey(token));
    }

    /**
     * The key a token is revoked under: the token with every character outside the Base64URL alphabet
     * removed, the dot separators aside.
     *
     * <p>Revocation used to key on the exact string submitted to {@code /revoke}, which let a revoked
     * JWT keep working under a different spelling (GHSA-x2rq-8p73-q36w). Nimbus decodes Base64URL
     * leniently and skips characters outside the alphabet, and the signature is verified from the
     * <em>decoded</em> bytes — so appending {@code =}, {@code \n}, {@code !} or {@code *} to the
     * signature segment produces a string that is not equal to the revoked one yet still verifies as
     * the same signed token. Presenting that spelling at {@code /userinfo} missed the revocation set
     * and was accepted, which makes "my application rejects a revoked token" pass while proving
     * nothing — the exact failure mode this store exists to close.
     *
     * <p>Collapsing those spellings to one key closes it, because every spelling Nimbus accepts as a
     * given token maps to the same key.
     *
     * <p>Opaque tokens are matched exactly elsewhere ({@link #opaqueTokens}), so this only widens what
     * counts as revoked. Two distinct opaque tokens differing solely by characters outside the
     * Base64URL alphabet would share a key and be revoked together; that is
     * fail-<em>closed</em>, consistent with revocation being global rather than per-provider, and the
     * tokens this provider mints cannot collide that way.
     */
    private static String revocationKey(String token) {
        StringBuilder key = new StringBuilder(token.length());
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (character == '.'
                || (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '-' || character == '_') {
                key.append(character);
            }
        }
        return key.toString();
    }

    /** Number of revoked tokens currently retained (test visibility). */
    int revokedTokenCount() {
        return revokedTokens.size();
    }

    public void reset() {
        providers.clear();
        codes.clear();
        deviceCodes.clear();
        opaqueTokens.clear();
        revokedTokens.clear();
    }

    /**
     * Immutable description of a generated OIDC provider. Carries the live {@link OidcTokenMinter}
     * (configuration + signing key pair) so the {@code /token} callback can mint tokens at request
     * time — embedding per-request context such as the authorize {@code nonce} — rather than serving
     * a pre-baked token JSON. The minter's key pair is the same one published at the JWKS endpoint.
     */
    public static class Provider {
        final String authorizePath;
        final String tokenPath;
        final String deviceAuthorizationPath;
        final OidcProviderConfiguration config;
        final OidcTokenMinter tokenMinter;

        public Provider(String authorizePath, String tokenPath, String deviceAuthorizationPath,
                        OidcProviderConfiguration config, OidcTokenMinter tokenMinter) {
            this.authorizePath = authorizePath;
            this.tokenPath = tokenPath;
            this.deviceAuthorizationPath = deviceAuthorizationPath;
            this.config = config;
            this.tokenMinter = tokenMinter;
        }

        public OidcProviderConfiguration getConfig() {
            return config;
        }

        public OidcTokenMinter getTokenMinter() {
            return tokenMinter;
        }
    }

    /**
     * Immutable record of an issued authorization code: the redirect_uri it was bound to, optional
     * PKCE challenge, the requested scope, and the {@code nonce} echoed from the authorize request.
     * The tokens are minted (not pre-baked) when the code is exchanged at {@code /token}.
     */
    public static class AuthorizationCode implements Serializable {
        final String redirectUri;
        final String codeChallenge;
        final String codeChallengeMethod;
        final String scope;
        final String nonce;
        /** Wall-clock time the code was stored ({@link #putCode}); drives TTL expiry. */
        volatile long issuedAtMillis;

        public AuthorizationCode(String redirectUri, String codeChallenge, String codeChallengeMethod,
                                 String scope, String nonce) {
            this.redirectUri = redirectUri;
            this.codeChallenge = codeChallenge;
            this.codeChallengeMethod = codeChallengeMethod;
            this.scope = scope;
            this.nonce = nonce;
        }
    }

    /**
     * Record of a device code issued by the device-authorization endpoint (RFC 8628). Carries the
     * paired {@code user_code}, the requested {@code scope}, and a counter of how many
     * {@code authorization_pending} polls remain before the device is "approved" and tokens are
     * minted (drives the {@link OidcProviderConfiguration#getDeviceCodePendingPolls()} mock model).
     */
    public static class DeviceCode implements Serializable {
        final String userCode;
        final String scope;
        /**
         * Remaining polls to answer with {@code authorization_pending} before approval. An
         * {@link AtomicInteger} so concurrent polls of the same device code decrement atomically — a
         * plain {@code volatile int} read-then-write loses decrements when polls race.
         */
        final AtomicInteger pendingPolls;
        /** Wall-clock time the code was stored ({@link #putDeviceCode}); drives TTL expiry. */
        volatile long issuedAtMillis;

        public DeviceCode(String userCode, String scope, int pendingPolls) {
            this.userCode = userCode;
            this.scope = scope;
            this.pendingPolls = new AtomicInteger(pendingPolls);
        }
    }

    /**
     * Record of an opaque access token (issued when {@link OidcProviderConfiguration#isOpaqueAccessToken()})
     * so the introspection endpoint can validate it: the claims to echo back and the absolute expiry.
     */
    public static class OpaqueToken implements Serializable {
        final Map<String, Object> claims;
        /** Absolute expiry epoch-seconds; {@code 0} when no expiry was recorded. */
        final long expiresAtEpochSeconds;

        public OpaqueToken(Map<String, Object> claims, long expiresAtEpochSeconds) {
            this.claims = new LinkedHashMap<>(claims);
            this.expiresAtEpochSeconds = expiresAtEpochSeconds;
        }

        boolean isExpired(long nowMillis) {
            return expiresAtEpochSeconds > 0 && nowMillis / 1000L >= expiresAtEpochSeconds;
        }
    }
}

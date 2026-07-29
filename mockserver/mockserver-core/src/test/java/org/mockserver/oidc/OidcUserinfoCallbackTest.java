package org.mockserver.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

/**
 * Conformance tests for the mock OIDC userinfo endpoint (OIDC Core §5.3).
 *
 * <p>Userinfo is an OAuth2 protected resource: it MUST require a bearer access token and MUST return
 * 401 when that token is missing or invalid. It was previously a static response that handed the
 * subject and every configured claim to any caller without looking at the {@code Authorization}
 * header — the same fabricated-success defect as the introspection endpoint, one endpoint over. Note
 * that every pre-existing userinfo test asserted only the happy path, which is why the defect
 * survived: no test ever presented a bad or absent token.
 */
public class OidcUserinfoCallbackTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OidcUserinfoCallback userinfo = new OidcUserinfoCallback();

    @Before
    @After
    public void resetStore() {
        OidcAuthorizationStore.getInstance().reset();
    }

    // --- the defect: userinfo must not serve claims without a valid token ---

    @Test
    public void shouldRejectRequestWithNoAuthorizationHeader() {
        generateProvider(new OidcProviderConfiguration());

        HttpResponse response = userinfo(null);

        assertThat(response.getStatusCode(), is(401));
        // RFC 6750 §3.1: when the request contains NO authentication credentials, the challenge must
        // NOT carry an error code — the client is being told to authenticate, not that its token was
        // rejected. Asserting only `containsString("Bearer")` cannot tell the two challenges apart,
        // which let a mutation collapsing this branch survive.
        assertThat(response.getFirstHeader("WWW-Authenticate"), is("Bearer"));
        assertThat(response.getFirstHeader("WWW-Authenticate"), not(containsString("error")));
    }

    @Test
    public void shouldDistinguishMissingCredentialsFromAnInvalidToken() {
        String accessToken = accessTokenFrom(generateProvider(new OidcProviderConfiguration()));

        // Missing credentials → bare challenge. Present-but-bad credentials → error="invalid_token".
        // A client uses this distinction to decide between "authenticate" and "refresh/re-login".
        HttpResponse missingCredentials = userinfo(null);
        HttpResponse invalidToken = userinfo("Bearer " + accessToken + "tampered");

        assertThat(missingCredentials.getFirstHeader("WWW-Authenticate"), is("Bearer"));
        assertThat(invalidToken.getFirstHeader("WWW-Authenticate"),
            containsString("error=\"invalid_token\""));

        // The body must agree with the challenge, not contradict it — asserting only the header let the
        // two drift, with an {"error":"invalid_token"} body served alongside a bare `Bearer` challenge.
        assertThat(missingCredentials.getBodyAsString(), is(""));
        assertThat(invalidToken.getBodyAsString(), containsString("invalid_token"));
    }

    @Test
    public void shouldRejectArbitraryBearerToken() {
        generateProvider(new OidcProviderConfiguration());

        HttpResponse response = userinfo("Bearer not-a-real-token");

        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getFirstHeader("WWW-Authenticate"), containsString("invalid_token"));
    }

    @Test
    public void shouldNotLeakClaimsWhenRejecting() throws Exception {
        // The whole point: an unauthorised caller must not receive the subject or any configured claim.
        OidcProviderConfiguration config = new OidcProviderConfiguration().setSubject("secret-subject");
        config.getAdditionalClaims().put("internal_role", "admin");
        generateProvider(config);

        String body = userinfo("Bearer garbage").getBodyAsString();

        assertThat(body, not(containsString("secret-subject")));
        assertThat(body, not(containsString("internal_role")));
        assertThat(body, not(containsString("admin")));
    }

    @Test
    public void shouldRejectMalformedAuthorizationSchemes() {
        generateProvider(new OidcProviderConfiguration());

        // Not a Bearer credential at all, and a Bearer header with no token.
        assertThat(userinfo("Basic dXNlcjpwYXNz").getStatusCode(), is(401));
        assertThat(userinfo("Bearer").getStatusCode(), is(401));
        assertThat(userinfo("Bearer    ").getStatusCode(), is(401));
        assertThat(userinfo("").getStatusCode(), is(401));
    }

    @Test
    public void shouldRejectExpiredToken() {
        OidcProviderConfiguration config = new OidcProviderConfiguration().setIssueExpiredToken(true);
        String expiredToken = accessTokenFrom(generateProvider(config));

        // "my application handles a 401 from userinfo when the access token expired" — previously
        // impossible to make fail.
        assertThat(userinfo("Bearer " + expiredToken).getStatusCode(), is(401));
    }

    @Test
    public void shouldRejectRevokedToken() {
        OidcProviderConfiguration config = new OidcProviderConfiguration();
        String accessToken = accessTokenFrom(generateProvider(config));

        assertThat("precondition: the token works before revocation",
            userinfo("Bearer " + accessToken).getStatusCode(), is(200));

        new OidcRevocationCallback().handle(request()
            .withMethod("POST").withPath("/revoke").withBody("token=" + accessToken));

        assertThat(userinfo("Bearer " + accessToken).getStatusCode(), is(401));
    }

    /**
     * Regression test for GHSA-x2rq-8p73-q36w.
     *
     * <p>Revocation used to key on the exact string submitted to {@code /revoke}. Nimbus decodes
     * Base64URL leniently, skipping characters outside the alphabet, and verifies the signature from
     * the decoded bytes — so each spelling below is a different string that still verifies as the same
     * signed token. Presenting one after revoking the original missed the revocation set and was
     * accepted, letting a revoked token keep working.
     */
    @Test
    public void shouldRejectRevokedTokenPresentedUnderAnAlternateSpelling() {
        // Characters Nimbus ignores when decoding the signature segment. Each yields a string that is
        // NOT equal to the revoked token yet verifies as it.
        String[] ignoredTrailingCharacters = {"=", "==", "\n", "!", "*"};

        for (String ignored : ignoredTrailingCharacters) {
            OidcAuthorizationStore.getInstance().reset();
            String accessToken = accessTokenFrom(generateProvider(new OidcProviderConfiguration()));
            String alternateSpelling = accessToken + ignored;

            assertThat("precondition: the alternate spelling is a different string",
                alternateSpelling.equals(accessToken), is(false));
            assertThat("precondition: the alternate spelling is accepted before revocation, so this "
                    + "test would fail for the right reason rather than because it is simply invalid",
                userinfo("Bearer " + alternateSpelling).getStatusCode(), is(200));

            new OidcRevocationCallback().handle(request()
                .withMethod("POST").withPath("/revoke").withBody("token=" + accessToken));

            assertThat("revoking the token must also revoke every spelling that verifies as it",
                userinfo("Bearer " + alternateSpelling).getStatusCode(), is(401));
        }
    }

    /**
     * The mirror of the above: revoking an alternate spelling must revoke the canonical token too,
     * otherwise the bypass simply runs in the other direction.
     */
    @Test
    public void shouldRejectCanonicalTokenAfterRevokingAnAlternateSpelling() {
        String accessToken = accessTokenFrom(generateProvider(new OidcProviderConfiguration()));

        assertThat("precondition: the token works before revocation",
            userinfo("Bearer " + accessToken).getStatusCode(), is(200));

        new OidcRevocationCallback().handle(request()
            .withMethod("POST").withPath("/revoke").withBody("token=" + accessToken + "="));

        assertThat(userinfo("Bearer " + accessToken).getStatusCode(), is(401));
    }

    @Test
    public void shouldRejectTokenFromADifferentProvider() {
        OidcProviderConfiguration foreignConfig = new OidcProviderConfiguration()
            .setUserinfoPath("/foreign-userinfo")
            .setIntrospectPath("/foreign-introspect")
            .setTokenPath("/foreign-token")
            .setAuthorizePath("/foreign-authorize")
            .setDeviceAuthorizationPath("/foreign-device");
        String foreignToken = accessTokenFrom(generateProvider(foreignConfig));

        OidcAuthorizationStore.getInstance().reset();
        generateProvider(new OidcProviderConfiguration());

        assertThat(userinfo("Bearer " + foreignToken).getStatusCode(), is(401));
    }

    // --- the happy path still works ---

    @Test
    public void shouldReturnClaimsForAValidToken() throws Exception {
        OidcProviderConfiguration config = new OidcProviderConfiguration().setSubject("alice");
        config.getAdditionalClaims().put("email", "alice@example.com");
        String accessToken = accessTokenFrom(generateProvider(config));

        HttpResponse response = userinfo("Bearer " + accessToken);
        assertThat(response.getStatusCode(), is(200));

        JsonNode body = OBJECT_MAPPER.readTree(response.getBodyAsString());
        // sub comes from the presented token, so userinfo describes the subject it was issued for.
        assertThat(body.path("sub").asText(), is("alice"));
        assertThat(body.path("email").asText(), is("alice@example.com"));
    }

    @Test
    public void shouldAcceptCaseInsensitiveBearerSchemeName() {
        // RFC 7235 §2.1: the auth-scheme name is case-insensitive.
        String accessToken = accessTokenFrom(generateProvider(new OidcProviderConfiguration()));

        assertThat(userinfo("bearer " + accessToken).getStatusCode(), is(200));
        assertThat(userinfo("BEARER " + accessToken).getStatusCode(), is(200));
    }

    @Test
    public void shouldWorkWithOpaqueAccessTokens() {
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setOpaqueAccessToken(true)
            .setSubject("opaque-sub");
        String opaqueToken = accessTokenFrom(generateProvider(config));

        assertThat(userinfo("Bearer " + opaqueToken).getStatusCode(), is(200));
        assertThat(userinfo("Bearer mock-opaque-never-issued").getStatusCode(), is(401));
    }

    @Test
    public void shouldSetNoStoreCacheDirectives() {
        String accessToken = accessTokenFrom(generateProvider(new OidcProviderConfiguration()));

        HttpResponse response = userinfo("Bearer " + accessToken);
        assertThat(response.getFirstHeader("cache-control"), is("no-store"));
    }

    // --- helpers ---

    private OidcAuthorizationStore.Provider generateProvider(OidcProviderConfiguration config) {
        new OidcProviderGenerator().generate(config);
        return OidcAuthorizationStore.getInstance().latestProvider();
    }

    private String accessTokenFrom(OidcAuthorizationStore.Provider provider) {
        try {
            String tokenResponse = provider.getTokenMinter()
                .mintTokenResponse("openid profile email", null, false, "http://localhost:1080");
            return OBJECT_MAPPER.readTree(tokenResponse).path("access_token").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResponse userinfo(String authorizationHeader) {
        org.mockserver.model.HttpRequest httpRequest = request()
            .withMethod("GET")
            .withPath("/userinfo")
            .withHeader("host", "localhost:1080");
        if (authorizationHeader != null) {
            httpRequest = httpRequest.withHeader("authorization", authorizationHeader);
        }
        return (HttpResponse) userinfo.handle(httpRequest);
    }
}

package org.mockserver.authentication;

import org.junit.Test;
import org.mockserver.model.Cookie;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.model.HttpRequest.request;

public class CredentialRedactionTest {

    @Test
    public void shouldMaskEveryCredentialHeaderAndDropCookiesWithoutChangingTheOriginal() {
        HttpRequest original = request("/mockserver/expectation")
            .withHeader("Authorization", "Basic YWRtaW46czNjcmV0")
            .withHeader("proxy-authorization", "Basic cHJveHk6cGFzcw==")
            .withHeader("X-Api-Key", "key-123")
            .withHeader("Accept", "application/json")
            .withCookie(new Cookie("session", "cookie-secret"));

        HttpRequest redacted = CredentialRedaction.withoutCredentials(original);

        String text = redacted.toString();
        assertThat(text, not(containsString("YWRtaW46czNjcmV0")));
        assertThat(text, not(containsString("cHJveHk6cGFzcw==")));
        assertThat(text, not(containsString("key-123")));
        assertThat(text, not(containsString("cookie-secret")));
        assertThat(redacted.getFirstHeader("Authorization"), equalTo("***REDACTED***"));
        assertThat(redacted.getFirstHeader("Accept"), equalTo("application/json"));
        assertThat(redacted.getPath().getValue(), equalTo("/mockserver/expectation"));
        assertThat(original.getFirstHeader("Authorization"), equalTo("Basic YWRtaW46czNjcmV0"));
    }

    @Test
    public void shouldMaskAControlPlaneRequestOnAnyLogEntryWhetherOrNotTheGateMarkedIt() {
        HttpRequest unmarked = request("/mockserver/expectation").withMethod("PUT").withHeader("Authorization", "Basic YWRtaW46czNjcmV0");
        HttpRequest bareAlias = request("/expectation").withMethod("PUT").withHeader("Authorization", "Bearer alias-token");
        io.netty.handler.codec.http.DefaultHttpRequest netty = new io.netty.handler.codec.http.DefaultHttpRequest(
            io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.PUT, "/mockserver/clear?type=all");
        netty.headers().set("Authorization", "Bearer netty-token");

        org.mockserver.log.model.LogEntry entry = new org.mockserver.log.model.LogEntry()
            .setHttpRequest(unmarked)
            .setMessageFormat("request:{}alias:{}netty:{}curl:{}")
            .setArguments(unmarked, bareAlias, netty, org.mockserver.log.model.DeferredLogArgument.curl(
                new org.mockserver.serialization.curl.HttpRequestToCurlSerializer(new org.mockserver.logging.MockServerLogger()), unmarked, null));

        String logged = java.util.Arrays.toString(entry.getHttpRequests()) + entry.getMessage();
        assertThat(logged, containsString("/mockserver/expectation"));
        assertThat(logged, containsString("***REDACTED***"));
        assertThat(logged, not(containsString("YWRtaW46czNjcmV0")));
        assertThat(logged, not(containsString("alias-token")));
        assertThat(logged, not(containsString("netty-token")));
        assertThat(unmarked.getFirstHeader("Authorization"), equalTo("Basic YWRtaW46czNjcmV0"));
    }

    @Test
    public void shouldMaskARawRequestToAControlPlaneRouteInAbsoluteForm() {
        io.netty.handler.codec.http.DefaultHttpRequest absolute = new io.netty.handler.codec.http.DefaultHttpRequest(
            io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.PUT, "http://127.0.0.1:1080/mockserver/expectation?x=1");
        absolute.headers().set("Authorization", "Basic YWRtaW46czNjcmV0");

        String logged = new org.mockserver.log.model.LogEntry().setMessageFormat("request:{}").setArguments(absolute).getMessage();

        assertThat(org.mockserver.mock.HttpState.isControlPlaneRequestTarget("PUT", absolute.uri()), equalTo(true));
        assertThat(logged, containsString("/mockserver/expectation"));
        assertThat(logged, not(containsString("YWRtaW46czNjcmV0")));
    }

    @Test
    public void shouldAlwaysMaskTheCredentialsOfADiagnosticCopyOfAHalfReceivedRequest() {
        for (String target : java.util.Arrays.asList("/api/upload", "http://127.0.0.1:1080/mockserver/expectation", "/%6Dockserver/expectation", "/MockServer/expectation")) {
            io.netty.handler.codec.http.DefaultHttpRequest netty = new io.netty.handler.codec.http.DefaultHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.PUT, target);
            netty.headers().set("Authorization", "Basic YWRtaW46czNjcmV0").set("Cookie", "session=cookie-secret");

            HttpRequest diagnostic = org.mockserver.mappers.NettyMessageForLog.request(netty);
            String logged = java.util.Arrays.toString(new org.mockserver.log.model.LogEntry().setHttpRequest(diagnostic).setArguments(diagnostic).getHttpRequests());

            assertThat(target, diagnostic.getFirstHeader("Authorization"), equalTo("***REDACTED***"));
            assertThat(target, logged, not(containsString("YWRtaW46czNjcmV0")));
            assertThat(target, logged, not(containsString("cookie-secret")));
        }
        io.netty.handler.codec.http.DefaultHttpRequest absolute = new io.netty.handler.codec.http.DefaultHttpRequest(
            io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.PUT, "http://127.0.0.1:1080/mockserver/expectation?x=1");
        assertThat("the path is the one the router dispatches on", org.mockserver.mappers.NettyMessageForLog.request(absolute).getPath().getValue(), equalTo("/mockserver/expectation"));
    }

    @Test
    public void shouldStillScrubAMaskedCredentialFromTheEntrysOtherTextWhenRedactingTheLog() {
        HttpRequest masked = CredentialRedaction.withoutCredentials(request("/mockserver/expectation").withHeader("Authorization", "Bearer quoted-token-value"));
        org.mockserver.log.model.LogEntry entry = new org.mockserver.log.model.LogEntry()
            .setHttpRequest(masked)
            .setMessageFormat("raw text:{}")
            .setArguments("header was Bearer quoted-token-value");

        assertThat(entry.getMessage(org.mockserver.configuration.Configuration.configuration().redactSecretsInLog(true)), not(containsString("quoted-token-value")));
        assertThat("never serialized", masked.toString(), not(containsString("quoted-token-value")));
    }

    @Test
    public void shouldMaskAControlPlaneRequestArgumentWhicheverOrderTheEntryIsBuiltIn() {
        HttpRequest controlPlane = request("/expectation").withMethod("PUT").withHeader("Authorization", "Basic YWRtaW46czNjcmV0");
        org.mockserver.serialization.curl.HttpRequestToCurlSerializer curl = new org.mockserver.serialization.curl.HttpRequestToCurlSerializer(new org.mockserver.logging.MockServerLogger());

        org.mockserver.log.model.LogEntry requestFirst = new org.mockserver.log.model.LogEntry()
            .setHttpRequest(controlPlane)
            .setMessageFormat("request:{}curl:{}")
            .setArguments(controlPlane, org.mockserver.log.model.DeferredLogArgument.curl(curl, controlPlane, null));
        org.mockserver.log.model.LogEntry argumentsFirst = new org.mockserver.log.model.LogEntry()
            .setMessageFormat("request:{}curl:{}")
            .setArguments(controlPlane, org.mockserver.log.model.DeferredLogArgument.curl(curl, controlPlane, null))
            .setHttpRequest(controlPlane);

        for (org.mockserver.log.model.LogEntry entry : java.util.Arrays.asList(requestFirst, argumentsFirst, requestFirst.clone())) {
            String logged = java.util.Arrays.toString(entry.getHttpRequests()) + entry.getMessage();
            assertThat(logged, containsString("***REDACTED***"));
            assertThat(logged, not(containsString("YWRtaW46czNjcmV0")));
        }
    }

    @Test
    public void shouldLogADataPlaneRequestAsSentWithoutCopyingIt() {
        HttpRequest dataPlane = request("/api/expectation").withMethod("PUT").withHeader("Authorization", "Bearer data-plane-token");
        HttpRequest lookAlike = request("/expectation").withMethod("GET").withHeader("Authorization", "Bearer look-alike-token");

        org.mockserver.log.model.LogEntry entry = new org.mockserver.log.model.LogEntry().setHttpRequest(dataPlane).setArguments(dataPlane, lookAlike);

        // read with redactSecretsInLog off on an instance, so a parallel test setting the global value cannot mask them
        Object[] arguments = entry.getArguments(org.mockserver.configuration.Configuration.configuration().redactSecretsInLog(false));
        assertThat(entry.getHttpRequests()[0], sameInstance(dataPlane));
        assertThat(arguments[0], sameInstance(dataPlane));
        assertThat(arguments[1], sameInstance(lookAlike));
    }

    @Test
    public void shouldPreserveCredentialsForAnOriginRouteExplicitlyMarkedAsDataPlane() {
        HttpRequest origin = request("/mockserver/expectation").withMethod("PUT")
            .withHeader("Authorization", "Bearer origin-token")
            .withCookie("session", "origin-cookie")
            .withProxyContext(null, true);

        for (HttpRequest candidate : java.util.Arrays.asList(origin, origin.clone(), origin.shallowClone())) {
            org.mockserver.log.model.LogEntry entry = new org.mockserver.log.model.LogEntry().setHttpRequest(candidate).setArguments(candidate);
            assertThat(entry.getHttpRequests()[0], sameInstance(candidate));
            assertThat(entry.getRedactedHttpRequest(org.mockserver.configuration.Configuration.configuration().redactSecretsInLog(false)), sameInstance(candidate));
            assertThat(candidate.getFirstHeader("Authorization"), equalTo("Bearer origin-token"));
            assertThat(candidate.getCookieList().get(0).getValue().getValue(), equalTo("origin-cookie"));
        }
    }

    @Test
    public void shouldRedactExplicitControlPlaneRequestsEvenIfTheyAlsoCarryADataPlaneMarker() {
        HttpRequest administrative = request("/mockserver/expectation").withMethod("PUT")
            .withHeader("Authorization", "Bearer admin-token")
            .withCookie("session", "admin-cookie")
            .withProxyContext(null, true)
            .markControlPlaneRequest();

        HttpRequest logged = (HttpRequest) CredentialRedaction.forLog(administrative);

        assertThat(logged.getFirstHeader("Authorization"), equalTo("***REDACTED***"));
        assertThat(logged.getCookieList().isEmpty(), equalTo(true));
    }

    @Test
    public void shouldOmitProxyCredentialsFromDataPlaneLogCopiesWithoutChangingTheLiveRequest() {
        String credential = ProxyAuthenticationValidator.expectedProxyAuthorizationHeaderValue("recorder", "proxy-password");
        for (boolean marked : new boolean[]{false, true}) {
            HttpRequest original = request("/api/orders").withMethod("POST")
                .withHeader("pRoXy-AuThOrIzAtIoN", credential)
                .withHeader("Authorization", "Bearer origin-token")
                .withCookie("session", "origin-cookie")
                .withBody("original body");
            original.withLogCorrelationId("same-exchange");
            if (marked) {
                original = original.withProxyContext(null, true);
            }

            HttpRequest projected = (HttpRequest) CredentialRedaction.forLog(original);

            assertThat(projected, not(sameInstance(original)));
            assertThat(projected.containsHeader("Proxy-Authorization"), equalTo(false));
            assertThat(projected.getFirstHeader("Authorization"), equalTo("Bearer origin-token"));
            assertThat(projected.getCookieList().get(0).getValue().getValue(), equalTo("origin-cookie"));
            assertThat(projected.getBodyAsString(), equalTo("original body"));
            assertThat(projected.getLogCorrelationId(), equalTo("same-exchange"));
            assertThat(projected.isDataPlaneRequest(), equalTo(marked));
            assertThat(ProxyAuthenticationValidator.isAuthenticated(original, "recorder", "proxy-password"), equalTo(true));
            assertThat(projected.clone().getMaskedCredentialValues(), equalTo(projected.getMaskedCredentialValues()));
            assertThat(projected.shallowClone().getMaskedCredentialValues(), equalTo(projected.getMaskedCredentialValues()));
            assertThat(projected.toString(), not(containsString(credential.substring("Basic ".length()))));
        }
    }

    @Test
    public void shouldFailClosedWithoutThrowingForARequestThatCannotBeRead() {
        HttpRequest unreadable = org.mockito.Mockito.spy(request("/mockserver/expectation").withHeader("Authorization", "Basic YWRtaW46czNjcmV0"));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(unreadable).getMethod();

        HttpRequest logged = (HttpRequest) CredentialRedaction.forLog(unreadable);
        HttpRequest attached = (HttpRequest) new org.mockserver.log.model.LogEntry().setHttpRequest(unreadable).getHttpRequests()[0];

        for (HttpRequest standIn : java.util.Arrays.asList(logged, attached)) {
            assertThat("never the original", standIn, not(sameInstance(unreadable)));
            assertThat(standIn.getHeaderList().isEmpty(), equalTo(true));
            assertThat(standIn.getPath().getValue(), equalTo("/mockserver/expectation"));
            assertThat(standIn.toString(), not(containsString("YWRtaW46czNjcmV0")));
        }
    }

    @Test
    public void shouldReturnARequestWithoutCredentialsUnchanged() {
        HttpRequest original = request("/x").withHeader("Accept", "*/*");

        assertThat(CredentialRedaction.withoutCredentials(original), sameInstance(original));
        assertThat(CredentialRedaction.withoutCredentials(null), equalTo(null));
    }
}

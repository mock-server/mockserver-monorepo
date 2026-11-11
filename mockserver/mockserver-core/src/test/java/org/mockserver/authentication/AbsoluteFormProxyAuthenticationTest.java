package org.mockserver.authentication;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

public class AbsoluteFormProxyAuthenticationTest {

    private static final Configuration CONFIGURED = configuration().proxyAuthenticationUsername("user").proxyAuthenticationPassword("password");
    private static final Set<String> LOCAL = Set.of("127.0.0.1:1080", "localhost:1080");

    private static HttpRequest absoluteForm(String host) {
        HttpRequest request = request("/path").withAbsoluteFormRequestTarget(true);
        return host == null ? request : request.withHeader("Host", host);
    }

    @Test
    public void shouldRequireCredentialsForAnAbsoluteFormRequestForAnotherHost() {
        assertThat(AbsoluteFormProxyAuthentication.credentialsMissing(CONFIGURED, absoluteForm("upstream:80"), LOCAL, false), is(true));
        assertThat("no Host", AbsoluteFormProxyAuthentication.credentialsMissing(CONFIGURED, absoluteForm(null), LOCAL, false), is(true));
        assertThat("no local addresses", AbsoluteFormProxyAuthentication.credentialsMissing(CONFIGURED, absoluteForm("localhost:1080"), null, false), is(true));
        assertThat("exact match only", AbsoluteFormProxyAuthentication.credentialsMissing(CONFIGURED, absoluteForm("LOCALHOST:1080"), LOCAL, false), is(true));
    }

    @Test
    public void shouldAcceptTheConfiguredCredentials() {
        HttpRequest request = absoluteForm("upstream:80")
            .withHeader("Proxy-Authorization", "Basic " + Base64.getEncoder().encodeToString("user:password".getBytes(StandardCharsets.UTF_8)));

        assertThat(AbsoluteFormProxyAuthentication.credentialsMissing(CONFIGURED, request, LOCAL, false), is(false));
    }

    @Test
    public void shouldNotRequireCredentialsWhenNotAProxyRequestForAnotherHost() {
        assertThat("origin-form", AbsoluteFormProxyAuthentication.credentialsMissing(CONFIGURED, request("/path").withHeader("Host", "upstream:80"), LOCAL, false), is(false));
        assertThat("for MockServer itself", AbsoluteFormProxyAuthentication.credentialsMissing(CONFIGURED, absoluteForm("localhost:1080"), LOCAL, false), is(false));
        assertThat("in a tunnel", AbsoluteFormProxyAuthentication.credentialsMissing(CONFIGURED, absoluteForm("upstream:80"), LOCAL, true), is(false));
        assertThat("not configured", AbsoluteFormProxyAuthentication.credentialsMissing(configuration(), absoluteForm("upstream:80"), LOCAL, false), is(false));
    }
}

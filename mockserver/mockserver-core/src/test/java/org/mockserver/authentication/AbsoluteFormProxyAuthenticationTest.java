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
        assertThat(AbsoluteFormProxyAuthentication.requiresCredentials(CONFIGURED, absoluteForm("upstream:80"), LOCAL, false), is(true));
        assertThat("no Host", AbsoluteFormProxyAuthentication.requiresCredentials(CONFIGURED, absoluteForm(null), LOCAL, false), is(true));
        assertThat("no local addresses", AbsoluteFormProxyAuthentication.requiresCredentials(CONFIGURED, absoluteForm("localhost:1080"), null, false), is(true));
        assertThat("exact match only", AbsoluteFormProxyAuthentication.requiresCredentials(CONFIGURED, absoluteForm("LOCALHOST:1080"), LOCAL, false), is(true));
    }

    @Test
    public void shouldRequireCredentialsWhateverTheRequestCarries() {
        // the caller decides with authenticateAndRemoveCredentials, which has already removed the header
        HttpRequest withValidCredential = absoluteForm("upstream:80")
            .withHeader("Proxy-Authorization", "Basic " + Base64.getEncoder().encodeToString("user:password".getBytes(StandardCharsets.UTF_8)));

        assertThat(AbsoluteFormProxyAuthentication.requiresCredentials(CONFIGURED, withValidCredential, LOCAL, false), is(true));
    }

    @Test
    public void shouldNotRequireCredentialsWhenNotAProxyRequestForAnotherHost() {
        assertThat("origin-form", AbsoluteFormProxyAuthentication.requiresCredentials(CONFIGURED, request("/path").withHeader("Host", "upstream:80"), LOCAL, false), is(false));
        assertThat("for MockServer itself", AbsoluteFormProxyAuthentication.requiresCredentials(CONFIGURED, absoluteForm("localhost:1080"), LOCAL, false), is(false));
        assertThat("in a tunnel", AbsoluteFormProxyAuthentication.requiresCredentials(CONFIGURED, absoluteForm("upstream:80"), LOCAL, true), is(false));
        assertThat("not configured", AbsoluteFormProxyAuthentication.requiresCredentials(configuration(), absoluteForm("upstream:80"), LOCAL, false), is(false));
    }
}

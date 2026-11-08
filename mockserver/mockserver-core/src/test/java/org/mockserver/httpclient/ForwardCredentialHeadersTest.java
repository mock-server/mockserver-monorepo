package org.mockserver.httpclient;

import org.junit.Test;
import org.mockserver.authentication.CredentialRedaction;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ForwardCredentialHeadersByHost;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.SocketAddress;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

public class ForwardCredentialHeadersTest {

    private static final Map<String, String> ENVIRONMENT = Map.of(
        "API_TOKEN", "Bearer sk-live-api-token",
        "TENANT_ID", "tenant-a",
        "BILLING_KEY", "billing-secret-key",
        "PORT_TOKEN", "Bearer port-specific-token",
        "PADDED", "  padded-value\n",
        "EMPTY", "   ",
        "WITH_NEWLINE", "first\r\nX-Injected: yes",
        "NOT_LATIN_1", "Bearer tok\u20ACen"
    );

    private static class CapturingMockServerLogger extends MockServerLogger {
        private final List<LogEntry> entries = new CopyOnWriteArrayList<>();

        CapturingMockServerLogger(Configuration configuration) {
            super(configuration, ForwardCredentialHeadersTest.class);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            entries.add(logEntry);
        }

        List<String> messages(Level level) {
            return entries.stream().filter(entry -> entry.getLogLevel() == level).map(LogEntry::getMessage).collect(Collectors.toList());
        }

        String everything() {
            return entries.stream().map(LogEntry::getMessage).collect(Collectors.joining("\n"));
        }
    }

    private final CapturingMockServerLogger logger = new CapturingMockServerLogger(configuration().logLevel(Level.INFO));

    private ForwardCredentialHeaders resolve(String value) {
        return ForwardCredentialHeaders.resolve(value, ENVIRONMENT::get, logger);
    }

    private ForwardCredentialHeaders forConfiguration(Configuration configuration) {
        return ForwardCredentialHeaders.forConfiguration(configuration, logger, new NettySslContextFactory(configuration, new MockServerLogger(), false));
    }

    private static InetSocketAddress at(String host, int port) {
        return InetSocketAddress.createUnresolved(host, port);
    }

    // a request as the forward client sends it over TLS to host, which names host in its Host header
    private static HttpRequest tlsTo(String host) {
        return request("/path").withSecure(true).withHeader("Host", host);
    }

    @Test
    public void shouldAddTheHeaderToACopyThatLogsAsTheOriginal() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:API_TOKEN");
        HttpRequest original = tlsTo("api.example.com").withHeader("x-other", "kept");

        HttpRequest sent = headers.applyTo(original, at("api.example.com", 443));

        assertThat(sent, not(sameInstance(original)));
        assertThat(sent.getHeader("Authorization"), contains("Bearer sk-live-api-token"));
        assertThat(sent.getFirstHeader("x-other"), is("kept"));
        assertThat(original.containsHeader("Authorization"), is(false));
        assertThat(sent.getRequestBeforeForwardCredentials(), sameInstance(original));
        assertThat(sent.clone().getRequestBeforeForwardCredentials(), sameInstance(original));
        assertThat(CredentialRedaction.forLog(sent), sameInstance(original));
        assertThat(new LogEntry().setHttpRequest(sent).setArguments(sent).getArguments()[0], sameInstance(original));
        assertThat(new LogEntry().setHttpRequest(sent).getHttpRequests()[0], sameInstance(original));
        assertThat(sent.toString(), is(original.toString()));
        assertThat(sent.toString(), not(containsString("sk-live-api-token")));
        assertThat(String.valueOf(sent), not(containsString("sk-live-api-token")));
    }

    @Test
    public void shouldReplaceAHeaderOfTheSameNameTheClientSent() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:API_TOKEN");
        HttpRequest original = tlsTo("api.example.com").withHeader("authorization", "Bearer client-token");

        HttpRequest sent = headers.applyTo(original, at("api.example.com", 443));

        assertThat(sent.getHeader("Authorization"), contains("Bearer sk-live-api-token"));
        assertThat(sent.getHeaderList().stream().filter(header -> header.getName().getValue().equalsIgnoreCase("authorization")).count(), is(1L));
        assertThat(original.getHeader("Authorization"), contains("Bearer client-token"));
    }

    @Test
    public void shouldReturnTheSameRequestForAnotherHost() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:API_TOKEN");

        for (String other : List.of("other.example.com", "api.example.com.evil.test", "evil-api.example.com")) {
            HttpRequest original = tlsTo(other);
            assertThat(headers.applyTo(original, at(other, 443)), sameInstance(original));
        }
    }

    @Test
    public void shouldNotAddTheHeaderWhenTheHostHeaderNamesAnotherHostThanTheConnection() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:API_TOKEN");
        HttpRequest spoofedHost = tlsTo("attacker.example");
        HttpRequest spoofedConnection = tlsTo("api.example.com");
        HttpRequest otherPort = tlsTo("api.example.com:8443");

        assertThat(headers.applyTo(spoofedHost, at("api.example.com", 443)), sameInstance(spoofedHost));
        assertThat(headers.applyTo(spoofedConnection, at("attacker.example", 443)), sameInstance(spoofedConnection));
        assertThat(headers.applyTo(otherPort, at("api.example.com", 443)), sameInstance(otherPort));
    }

    @Test
    public void shouldNotAddTheHeaderWhenTheSocketAddressAndHostHeaderDisagree() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:API_TOKEN");
        HttpRequest overridden = tlsTo("attacker.example").withSocketAddress("api.example.com", 443, SocketAddress.Scheme.HTTPS);
        HttpRequest agreeing = tlsTo("api.example.com").withSocketAddress("api.example.com", 443, SocketAddress.Scheme.HTTPS);

        assertThat(headers.applyTo(overridden, null), sameInstance(overridden));
        assertThat(headers.applyTo(agreeing, null).getFirstHeader("Authorization"), is("Bearer sk-live-api-token"));
    }

    @Test
    public void shouldNotAddTheHeaderWithoutAHostHeader() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:API_TOKEN");
        HttpRequest noHost = request("/path").withSecure(true);

        assertThat(headers.applyTo(noHost, at("api.example.com", 443)), sameInstance(noHost));
        assertThat(headers.applyTo(noHost, null), sameInstance(noHost));
    }

    @Test
    public void shouldNotAddTheHeaderWhenTheRequestTargetIsNotAPath() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:API_TOKEN,http://api.example.com:80=Authorization:API_TOKEN");
        List<String> notPaths = List.of(".evil.com/x", "@evil.com/x", "*", "", "https://evil.com/x", "https://api.example.com@evil.com/x",
            "https://api.example.com/x", "http://api.example.com/collect", "x.evil.example://api.example.com:80/collect", "x.evil.example://api.example.com/collect");

        for (String path : notPaths) {
            HttpRequest overTls = tlsTo("api.example.com").withPath(path);
            HttpRequest overPlainHttp = request(path).withHeader("Host", "api.example.com");
            assertThat(path, headers.applyTo(overTls, at("api.example.com", 443)), sameInstance(overTls));
            assertThat(path, headers.applyTo(overPlainHttp, at("api.example.com", 80)), sameInstance(overPlainHttp));
        }
        HttpRequest emptyPathWithQuery = request("").withHeader("Host", "api.example.com").withQueryStringParameter("@evil.com", "");
        assertThat(headers.applyTo(emptyPathWithQuery, at("api.example.com", 80)), sameInstance(emptyPathWithQuery));
        for (String path : List.of("/x", "//double/slash", "/x?@evil.com")) {
            HttpRequest original = tlsTo("api.example.com").withPath(path);
            assertThat(path, headers.applyTo(original, at("api.example.com", 443)).containsHeader("Authorization"), is(true));
        }
    }

    @Test
    public void shouldAddTheHeaderOverPlainHttpOnlyWhenTheHostHeaderNamesTheDestination() {
        ForwardCredentialHeaders headers = resolve("http://api.example.com:80=Authorization:API_TOKEN");

        assertThat(headers.applyTo(request("/collect").withHeader("Host", "api.example.com"), at("api.example.com", 80)).containsHeader("Authorization"), is(true));
        assertThat(headers.applyTo(request("/collect").withHeader("Host", "API.example.com:80"), at("api.example.com", 80)).containsHeader("Authorization"), is(true));
        for (String host : List.of("api.example.com?@evil.com", "api.example.com#@evil.com", "api.example.com@evil.com", "api.example.com/evil.com",
            "api.example.com\\@evil.com", "api.example.com.evil.com", "api.example.com:8080", "api.example.com, evil.com", "169.254.169.254?.api.example.com")) {
            HttpRequest original = request("/collect").withHeader("Host", host);
            assertThat(host, headers.applyTo(original, at("api.example.com", 80)), sameInstance(original));
        }
    }

    @Test
    public void shouldNotAddTheHeaderWhenThereIsMoreThanOneHostHeader() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:API_TOKEN,http://api.example.com:80=Authorization:API_TOKEN");
        HttpRequest twoHostsOverTls = tlsTo("api.example.com").withHeader("Host", "evil.example");
        HttpRequest sameHostTwice = tlsTo("api.example.com").withHeader("host", "api.example.com");
        HttpRequest twoHostsOverPlainHttp = request("/collect").withHeader("Host", "api.example.com", "evil.example");

        assertThat(headers.applyTo(twoHostsOverTls, at("api.example.com", 443)), sameInstance(twoHostsOverTls));
        assertThat(headers.applyTo(sameHostTwice, at("api.example.com", 443)), sameInstance(sameHostTwice));
        assertThat(headers.applyTo(twoHostsOverPlainHttp, at("api.example.com", 80)), sameInstance(twoHostsOverPlainHttp));
    }

    @Test
    public void shouldNeverMatchAZoneScopedIpv6Destination() {
        ForwardCredentialHeaders headers = resolve("[fe80::1]:8443=Authorization:API_TOKEN");
        HttpRequest zoneInHostHeader = tlsTo("[fe80::1%25eth0]:8443");
        HttpRequest zoneInConnection = tlsTo("[fe80::1]:8443");

        assertThat(headers.applyTo(zoneInHostHeader, null), sameInstance(zoneInHostHeader));
        assertThat(headers.applyTo(zoneInConnection, at("fe80::1%eth0", 8443)), sameInstance(zoneInConnection));
        assertThat(headers.headersFor("fe80::1%eth0", 8443, true), is(empty()));
        assertThat(headers.applyTo(zoneInConnection, at("fe80::1", 8443)).containsHeader("Authorization"), is(true));

        logger.entries.clear();
        assertThat(resolve("[fe80::1%eth0]:8443=Authorization:API_TOKEN"), is(nullValue()));
        assertThat(logger.messages(Level.WARN), contains(containsString("its host is not an ASCII host name, IP address")));
    }

    @Test
    public void shouldNotSendAValueWithACharacterAHeaderCannotCarry() {
        ForwardCredentialHeaders headers = resolve("api.example.com=X-Unicode:NOT_LATIN_1;Authorization:API_TOKEN");

        assertThat(headers.headersFor("api.example.com", 443, true), contains(new Header("Authorization", "Bearer sk-live-api-token")));
        List<String> warnings = logger.messages(Level.WARN);
        assertThat(warnings, contains(allOf(containsString("entry 1 header 1"), containsString("above U+00FF"))));
        assertThat(String.join("\n", warnings), not(anyOf(containsString("NOT_LATIN_1"), containsString("tok"))));
    }

    @Test
    public void shouldOnlyAddTheHeaderOverTlsUnlessTheEntryOptsIntoPlainHttpForAPort() {
        ForwardCredentialHeaders tlsOnly = resolve("api.example.com=Authorization:API_TOKEN");
        ForwardCredentialHeaders plaintext = resolve("http://api.example.com:8080=Authorization:API_TOKEN");
        HttpRequest overPlainHttp = request("/path").withHeader("Host", "api.example.com:8080");
        HttpRequest overTls = tlsTo("api.example.com:8080");

        assertThat(tlsOnly.applyTo(overPlainHttp, at("api.example.com", 8080)), sameInstance(overPlainHttp));
        assertThat(tlsOnly.applyTo(request().withHeader("Host", "api.example.com"), at("api.example.com", 80)).containsHeader("Authorization"), is(false));
        assertThat(plaintext.applyTo(overPlainHttp, at("api.example.com", 8080)).getFirstHeader("Authorization"), is("Bearer sk-live-api-token"));
        assertThat(plaintext.applyTo(overTls, at("api.example.com", 8080)), sameInstance(overTls));
        assertThat(plaintext.applyTo(request().withHeader("Host", "api.example.com:8081"), at("api.example.com", 8081)).containsHeader("Authorization"), is(false));
    }

    @Test
    public void shouldIgnoreAPlainHttpEntryWithoutAPort() {
        assertThat(resolve("http://api.example.com=Authorization:API_TOKEN"), is(nullValue()));
        assertThat(logger.messages(Level.WARN), contains(containsString("a plain HTTP entry must name its port")));
    }

    @Test
    public void shouldAcceptAnExplicitHttpsPrefix() {
        ForwardCredentialHeaders headers = resolve("https://api.example.com=Authorization:API_TOKEN");

        assertThat(headers.headersFor("api.example.com", 443, true), hasSize(1));
        assertThat(headers.headersFor("api.example.com", 443, false), is(empty()));
    }

    @Test
    public void shouldUseTheHostHeaderWhenThereIsNoRemoteAddress() {
        ForwardCredentialHeaders headers = resolve("api.example.com:8443=Authorization:API_TOKEN");

        assertThat(headers.applyTo(tlsTo("API.example.com:8443"), null).getFirstHeader("Authorization"), is("Bearer sk-live-api-token"));
        assertThat(headers.applyTo(tlsTo("api.example.com:8444"), null).containsHeader("Authorization"), is(false));
        assertThat(headers.applyTo(tlsTo("api.example.com"), null).containsHeader("Authorization"), is(false));
    }

    @Test
    public void shouldMatchTheHostIgnoringCaseAndATrailingDot() {
        ForwardCredentialHeaders headers = resolve("API.Example.COM.=Authorization:API_TOKEN");

        assertThat(headers.headersFor("api.example.com", 443, true), hasSize(1));
        assertThat(headers.headersFor("Api.Example.Com.", 80, true), hasSize(1));
        assertThat(headers.applyTo(tlsTo("API.example.com."), at("api.example.com", 443)).containsHeader("Authorization"), is(true));
    }

    @Test
    public void shouldMatchOnlyThePortGivenAndEveryPortWhenNoneIsGiven() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:API_TOKEN,billing.internal:8443=X-Api-Key:BILLING_KEY");

        assertThat(headers.headersFor("api.example.com", 80, true), hasSize(1));
        assertThat(headers.headersFor("api.example.com", 9999, true), hasSize(1));
        assertThat(headers.headersFor("billing.internal", 8443, true), contains(new Header("X-Api-Key", "billing-secret-key")));
        assertThat(headers.headersFor("billing.internal", 443, true), is(empty()));
    }

    @Test
    public void shouldLetAnEntryWithAPortOverrideOneForTheHostAlone() {
        ForwardCredentialHeaders headers = resolve("api.example.com:8443=Authorization:PORT_TOKEN,api.example.com=Authorization:API_TOKEN");

        assertThat(headers.applyTo(tlsTo("api.example.com:8443"), at("api.example.com", 8443)).getHeader("Authorization"), contains("Bearer port-specific-token"));
        assertThat(headers.applyTo(tlsTo("api.example.com"), at("api.example.com", 443)).getHeader("Authorization"), contains("Bearer sk-live-api-token"));
    }

    @Test
    public void shouldSendSeveralHeadersToOneHostFromOneEntryOrRepeatedEntries() {
        ForwardCredentialHeaders oneEntry = resolve("api.example.com=Authorization:API_TOKEN;X-Tenant:TENANT_ID");
        ForwardCredentialHeaders repeated = resolve("api.example.com=Authorization:API_TOKEN, api.example.com=X-Tenant:TENANT_ID");

        for (ForwardCredentialHeaders headers : List.of(oneEntry, repeated)) {
            HttpRequest sent = headers.applyTo(tlsTo("api.example.com"), at("api.example.com", 443));
            assertThat(sent.getFirstHeader("Authorization"), is("Bearer sk-live-api-token"));
            assertThat(sent.getFirstHeader("X-Tenant"), is("tenant-a"));
        }
    }

    @Test
    public void shouldMatchAnIpv6HostHoweverItIsWritten() {
        ForwardCredentialHeaders bracketed = resolve("[::1]:8443=Authorization:API_TOKEN");
        ForwardCredentialHeaders unbracketed = resolve("0:0:0:0:0:0:0:1=Authorization:API_TOKEN");

        for (ForwardCredentialHeaders headers : List.of(bracketed, unbracketed)) {
            assertThat(headers.applyTo(tlsTo("[::1]:8443"), null).containsHeader("Authorization"), is(true));
            assertThat(headers.applyTo(tlsTo("[0:0:0:0:0:0:0:1]:8443"), at("::1", 8443)).containsHeader("Authorization"), is(true));
            assertThat(headers.applyTo(tlsTo("[::1]:8443").withSocketAddress("::1", 8443, SocketAddress.Scheme.HTTPS), null).containsHeader("Authorization"), is(true));
        }
        assertThat(bracketed.applyTo(tlsTo("[::1]:443"), null).containsHeader("Authorization"), is(false));
        assertThat(bracketed.applyTo(tlsTo("[::2]:8443"), null).containsHeader("Authorization"), is(false));
    }

    @Test
    public void shouldStripWhitespaceAroundAValue() {
        assertThat(resolve("api.example.com=X-Padded:PADDED").headersFor("api.example.com", 443, true), contains(new Header("X-Padded", "padded-value")));
    }

    @Test
    public void shouldWarnNamingTheEntryPositionButNotTheHostOrVariableWhenAVariableIsNotSetAndSendNothing() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:NOT_SET");

        assertThat(headers, is(nullValue()));
        List<String> warnings = logger.messages(Level.WARN);
        assertThat(warnings, contains(containsString("the environment variable for entry 1 header 1 is not set, so the header is not sent")));
        assertThat(warnings.get(0), not(anyOf(containsString("NOT_SET"), containsString("api.example.com"), containsString("Authorization"))));
    }

    @Test
    public void shouldNotRepeatAVariableNameThatCouldBeAPastedSecret() {
        resolve("api.example.com=Authorization:sk_live_abc123;X-Key:sk.live.abc123;X-Other:abc-123;X-Aws:AKIAIOSFODNN7EXAMPLE");

        String warnings = String.join("\n", logger.messages(Level.WARN));
        assertThat(logger.messages(Level.WARN), hasSize(4));
        assertThat(warnings, containsString("the environment variable for entry 1 header 1 is not set"));
        assertThat(warnings, containsString("the environment variable for entry 1 header 4 is not set"));
        assertThat(warnings, not(containsString("AKIAIOSFODNN7EXAMPLE")));
        assertThat(logger.messages(Level.WARN).stream().filter(warning -> warning.contains("the variable name has only letters, digits and _")).count(), is(2L));
        assertThat(warnings, not(anyOf(containsString("sk_live_abc123"), containsString("sk.live.abc123"), containsString("abc-123"))));
    }

    @Test
    public void shouldNotSendAnEmptyValueOrOneThatCouldInjectAHeader() {
        ForwardCredentialHeaders headers = resolve("api.example.com=X-Empty:EMPTY;X-Split:WITH_NEWLINE;Authorization:API_TOKEN");

        assertThat(headers.headersFor("api.example.com", 443, true), contains(new Header("Authorization", "Bearer sk-live-api-token")));
        List<String> warnings = logger.messages(Level.WARN);
        assertThat(warnings, hasSize(2));
        assertThat(warnings, hasItem(allOf(containsString("entry 1 header 1"), containsString("is empty"))));
        assertThat(warnings, hasItem(allOf(containsString("entry 1 header 2"), containsString("line break"))));
        assertThat(String.join("\n", warnings), not(containsString("X-Injected")));
    }

    @Test
    public void shouldIgnoreMalformedEntriesWithoutRepeatingWhatTheyContain() {
        ForwardCredentialHeaders headers = resolve(String.join(",",
            "no-equals-sign",
            "=Authorization:API_TOKEN",
            "api.example.com=",
            "api.example.com=Authorization:Bearer sk-pasted-secret",
            "api.example.com=Authorization",
            "api.example.com=Bad Header:API_TOKEN",
            "api.example.com:0=Authorization:API_TOKEN",
            "api.example.com:99999=Authorization:API_TOKEN",
            "api.example.com:port=Authorization:API_TOKEN",
            "user@api.example.com=Authorization:API_TOKEN",
            "ftp://api.example.com=Authorization:API_TOKEN",
            "[::1=Authorization:API_TOKEN",
            "good.example.com=X-Tenant:TENANT_ID"
        ));

        assertThat(headers.headersFor("good.example.com", 443, true), contains(new Header("X-Tenant", "tenant-a")));
        assertThat(headers.headersFor("api.example.com", 443, true), is(empty()));
        List<String> warnings = logger.messages(Level.WARN);
        assertThat(warnings, hasSize(12));
        assertThat(String.join("\n", warnings), not(containsString("sk-pasted-secret")));
        assertThat(String.join("\n", warnings), not(containsString("sk-live-api-token")));
        assertThat(String.join("\n", warnings), not(containsString("api.example.com")));
    }

    @Test
    public void shouldRefuseHeadersMockServerOrTheConnectionControls() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Host:API_TOKEN;Content-Length:API_TOKEN;Transfer-Encoding:API_TOKEN;Proxy-Authorization:API_TOKEN;Connection:API_TOKEN;Cookie:API_TOKEN");

        assertThat(headers, is(nullValue()));
        assertThat(logger.messages(Level.WARN), hasSize(6));
        assertThat(logger.messages(Level.WARN), everyItem(allOf(containsString("MockServer or the connection controls the"), not(containsString("api.example.com")))));
    }

    @Test
    public void shouldLogTheHostsAndHeaderNamesButNeverAValue() {
        resolve("api.example.com=Authorization:API_TOKEN;X-Tenant:TENANT_ID,billing.internal:8443=X-Api-Key:BILLING_KEY,http://plain.internal:8080=X-Api-Key:BILLING_KEY");

        List<String> info = logger.messages(Level.INFO);
        assertThat(info, hasSize(1));
        assertThat(info.get(0), allOf(containsString("api.example.com"), containsString("Authorization"), containsString("X-Tenant"), containsString("billing.internal:8443"), containsString("X-Api-Key"), containsString("http://plain.internal:8080")));
        assertThat(logger.everything(), not(anyOf(containsString("sk-live-api-token"), containsString("tenant-a"), containsString("billing-secret-key"))));
    }

    @Test
    public void shouldLogAtDebugWithoutAValueWhenTheHostHeaderDisagrees() {
        CapturingMockServerLogger debugLogger = new CapturingMockServerLogger(configuration().logLevel(Level.DEBUG));
        ForwardCredentialHeaders headers = ForwardCredentialHeaders.resolve("api.example.com=Authorization:API_TOKEN", ENVIRONMENT::get, debugLogger);

        headers.applyTo(tlsTo("attacker.example"), at("api.example.com", 443));

        assertThat(debugLogger.messages(Level.DEBUG), contains(allOf(containsString("api.example.com:443"), containsString("it does not have exactly one Host header naming that host and port"))));
        assertThat(debugLogger.everything(), not(containsString("sk-live-api-token")));
    }

    // PATH is set in every environment the build runs in, so a forward client resolves it without a test seam
    private static Configuration verifiedTls(String value) {
        return configuration().forwardCredentialHeadersByHost(value)
            .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.JVM)
            .forwardProxyTLSHostnameVerificationEnabled(true);
    }

    @Test
    public void shouldIgnoreTlsEntriesWhenTheForwardClientTrustsAnyCertificate() {
        ForwardCredentialHeaders headers = forConfiguration(configuration().forwardCredentialHeadersByHost("api.example.com=X-Path:PATH,http://api.example.com:8080=X-Path:PATH")
            .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.ANY)
            .forwardProxyTLSHostnameVerificationEnabled(true));

        assertThat(headers.headersFor("api.example.com", 443, true), is(empty()));
        assertThat(headers.headersFor("api.example.com", 8080, false), hasSize(1));
        assertThat(logger.messages(Level.WARN), contains(allOf(containsString("ignores its TLS entries"), containsString("forwardProxyTLSX509CertificatesTrustManagerType is ANY"), containsString("forwardProxyTLSHostnameVerificationEnabled"))));
        assertThat(forConfiguration(configuration().forwardCredentialHeadersByHost("api.example.com=X-Path:PATH")
            .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.ANY)), is(nullValue()));

        logger.entries.clear();
        assertThat(forConfiguration(verifiedTls("api.example.com=X-Path:PATH")).headersFor("api.example.com", 443, true), hasSize(1));
        assertThat(logger.messages(Level.WARN), is(empty()));
    }

    @Test
    public void shouldIgnoreTlsEntriesWhenTheForwardClientDoesNotVerifyHostNames() {
        ForwardCredentialHeaders headers = forConfiguration(configuration().forwardCredentialHeadersByHost("api.example.com=X-Path:PATH")
            .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.JVM)
            .forwardProxyTLSHostnameVerificationEnabled(false));

        assertThat(headers, is(nullValue()));
        assertThat(logger.messages(Level.WARN), contains(allOf(containsString("ignores its TLS entries"), containsString("forwardProxyTLSHostnameVerificationEnabled is false"))));
    }

    @Test
    public void shouldNotWarnAboutTlsTrustWhenEveryEntryIsPlainHttp() {
        for (ForwardProxyTLSX509CertificatesTrustManager trustManager : List.of(ForwardProxyTLSX509CertificatesTrustManager.ANY, ForwardProxyTLSX509CertificatesTrustManager.JVM)) {
            ForwardCredentialHeaders headers = forConfiguration(configuration().forwardCredentialHeadersByHost("http://api.example.com:8080=X-Path:PATH")
                .forwardProxyTLSX509CertificatesTrustManagerType(trustManager)
                .forwardProxyTLSHostnameVerificationEnabled(false));
            assertThat(headers.headersFor("api.example.com", 8080, false), hasSize(1));
        }

        assertThat(logger.messages(Level.WARN), is(empty()));
        assertThat(ForwardCredentialHeadersByHost.configuresTls("http://api.example.com:8080=X-Path:PATH"), is(false));
        assertThat(ForwardCredentialHeadersByHost.configuresTls("http://api.example.com:8080=X-Path:PATH,api.example.com=X-Path:PATH"), is(true));
    }

    @Test
    public void shouldKeepTheCredentialTlsContextItStartedWithWhateverTheForwardTlsSettingsBecome() {
        Configuration live = verifiedTls("api.example.com=X-Path:PATH");
        ForwardCredentialHeaders headers = forConfiguration(live);
        io.netty.handler.ssl.SslContext http1 = headers.sslContextFor(false, "api.example.com");
        io.netty.handler.ssl.SslContext http2 = headers.sslContextFor(true, "api.example.com");
        assertThat(http2, not(sameInstance(http1)));

        live.forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.ANY)
            .forwardProxyTLSHostnameVerificationEnabled(false)
            .forwardProxyTLSCustomTrustX509Certificates("org/mockserver/socket/CertificateAuthorityCertificate.pem");

        assertThat(headers.sslContextFor(false, "api.example.com"), sameInstance(http1));
        assertThat(headers.sslContextFor(true, "other.example.com"), sameInstance(http2));
        assertThat(headers.applyTo(tlsTo("api.example.com"), at("api.example.com", 443)).containsHeader("X-Path"), is(true));
    }

    @Test
    public void shouldRefuseATlsConnectionForTheHeadersWithoutACredentialContext() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:API_TOKEN");

        IllegalStateException refused = org.junit.Assert.assertThrows(IllegalStateException.class, () -> headers.sslContextFor(false, "api.example.com"));
        assertThat(refused.getMessage(), containsString("no TLS context"));
    }

    @Test
    public void shouldIgnoreTlsEntriesWhenTheCredentialTlsContextCannotBeBuilt() {
        ForwardCredentialHeaders headers = forConfiguration(configuration().forwardCredentialHeadersByHost("api.example.com=X-Path:PATH,http://api.example.com:8080=X-Path:PATH")
            .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM)
            .forwardProxyTLSCustomTrustX509Certificates("org/mockserver/authentication/mtls/ca.pem")
            .forwardProxyTLSHostnameVerificationEnabled(true)
            .forwardProxyPrivateKey("org/mockserver/authentication/mtls/ca.pem")
            .forwardProxyCertificateChain("org/mockserver/authentication/mtls/ca.pem"));

        assertThat(headers.headersFor("api.example.com", 443, true), is(empty()));
        assertThat(headers.headersFor("api.example.com", 8080, false), hasSize(1));
        assertThat(logger.messages(Level.WARN), contains(containsString("the TLS context for them could not be built")));
        assertThat(ForwardCredentialHeaders.forConfiguration(verifiedTls("api.example.com=X-Path:PATH"), logger, null), is(nullValue()));
    }

    @Test
    public void shouldIgnoreTlsEntriesWhenTheCustomTrustHoldsOnlyMockServersOwnCa() {
        Configuration configuration = configuration().forwardCredentialHeadersByHost("api.example.com=X-Path:PATH,http://api.example.com:8080=X-Path:PATH")
            .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM)
            .forwardProxyTLSCustomTrustX509Certificates("org/mockserver/socket/CertificateAuthorityCertificate.pem")
            .forwardProxyTLSHostnameVerificationEnabled(true);

        ForwardCredentialHeaders headers = ForwardCredentialHeaders.forConfiguration(configuration, logger, new NettySslContextFactory(configuration, logger, false));

        assertThat(headers.headersFor("api.example.com", 443, true), is(empty()));
        assertThat(headers.headersFor("api.example.com", 8080, false), hasSize(1));
        assertThat(logger.messages(Level.WARN), hasItem(allOf(containsString("forwardProxyTLSCustomTrustX509Certificates"), containsString("of MockServer's own CA or certificates"), containsString("left out"))));
        assertThat(logger.messages(Level.WARN), hasItem(allOf(containsString("ignores its TLS entries"), containsString("holds no certificate other than MockServer's own CA or certificate"))));
    }

    @Test
    public void shouldNeverUseTlsV1OrV11ForTheCredentialConnectionWhateverTheTlsSettingsAllow() {
        Configuration configuration = verifiedTls("api.example.com=X-Path:PATH").tlsProtocols("TLSv1,TLSv1.1,TLSv1.2").tlsAllowInsecureProtocols(true);

        ForwardCredentialHeaders headers = forConfiguration(configuration);

        for (boolean http2 : List.of(false, true)) {
            List<String> enabled = List.of(headers.sslContextFor(http2, "api.example.com").newEngine(io.netty.buffer.UnpooledByteBufAllocator.DEFAULT).getEnabledProtocols());
            assertThat(enabled, allOf(hasItem("TLSv1.2"), not(hasItem("TLSv1")), not(hasItem("TLSv1.1"))));
        }

        logger.entries.clear();
        assertThat(forConfiguration(verifiedTls("api.example.com=X-Path:PATH").tlsProtocols("TLSv1,TLSv1.1").tlsAllowInsecureProtocols(true)), is(nullValue()));
        assertThat(logger.messages(Level.WARN), hasItem(allOf(containsString("ignores its TLS entries"), containsString("tlsProtocols names only TLSv1 or TLSv1.1"))));
    }

    @Test
    public void shouldNotAddTheHeaderToATraceOrConnectRequest() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:API_TOKEN,http://api.example.com:80=Authorization:API_TOKEN");

        for (String method : List.of("TRACE", "trace", "CONNECT", "Connect")) {
            HttpRequest overTls = tlsTo("api.example.com").withMethod(method);
            HttpRequest overPlainHttp = request("/path").withMethod(method).withHeader("Host", "api.example.com");
            assertThat(method, headers.applyTo(overTls, at("api.example.com", 443)), sameInstance(overTls));
            assertThat(method, headers.applyTo(overPlainHttp, at("api.example.com", 80)), sameInstance(overPlainHttp));
        }
        for (String method : List.of("GET", "POST", "OPTIONS", "HEAD")) {
            assertThat(method, headers.applyTo(tlsTo("api.example.com").withMethod(method), at("api.example.com", 443)).containsHeader("Authorization"), is(true));
        }
    }

    @Test
    public void shouldRefuseAVariableMockServerReadsAsItsOwnConfigurationWithoutNamingIt() {
        ForwardCredentialHeaders headers = resolve("api.example.com=Authorization:MOCKSERVER_UPSTREAM_TOKEN;X-Lower:mockserver_lower_token;X-Tenant:TENANT_ID");

        assertThat(headers.headersFor("api.example.com", 443, true), contains(new Header("X-Tenant", "tenant-a")));
        List<String> warnings = logger.messages(Level.WARN);
        assertThat(warnings, hasSize(2));
        assertThat(warnings, everyItem(containsString("starts with MOCKSERVER_")));
        assertThat(String.join("\n", warnings), not(anyOf(containsString("UPSTREAM_TOKEN"), containsString("lower_token"))));
        assertThat(ForwardCredentialHeadersByHost.environmentVariables("api.example.com=Authorization:MOCKSERVER_UPSTREAM_TOKEN"), is(Map.of()));
    }

    @Test
    public void shouldMatchHostsExactlyRefusingNonAsciiPaddedAndBracketedNames() {
        ForwardCredentialHeaders headers = resolve("api.kafka.com=Authorization:API_TOKEN,http://api.kafka.com:80=Authorization:API_TOKEN");
        String kelvin = "api.\u212Aafka.com";

        HttpRequest kelvinHost = tlsTo(kelvin);
        assertThat(headers.applyTo(kelvinHost, at(kelvin, 443)), sameInstance(kelvinHost));
        assertThat(headers.applyTo(kelvinHost, at("api.kafka.com", 443)), sameInstance(kelvinHost));
        assertThat(headers.headersFor(kelvin, 443, true), is(empty()));
        assertThat(headers.headersFor("[api.kafka.com]", 443, true), is(empty()));
        for (String padded : List.of("\tapi.kafka.com", "api.kafka.com\t", " api.kafka.com", "api.kafka.com ", "api.kafka.com\u000b", "\u0001api.kafka.com", "api.kafka.com\r", "api.kafka.com:\u0664\u0664\u0663")) {
            HttpRequest overTls = tlsTo(padded);
            HttpRequest overPlainHttp = request("/x").withHeader("Host", padded);
            assertThat(padded, headers.applyTo(overTls, at("api.kafka.com", 443)), sameInstance(overTls));
            assertThat(padded, headers.applyTo(overPlainHttp, at("api.kafka.com", 80)), sameInstance(overPlainHttp));
        }
        HttpRequest bracketedName = tlsTo("[api.kafka.com]:443");
        assertThat(headers.applyTo(bracketedName, at("api.kafka.com", 443)), sameInstance(bracketedName));
        assertThat(headers.applyTo(tlsTo("api.kafka.com"), at("api.kafka.com", 443)).containsHeader("Authorization"), is(true));

        logger.entries.clear();
        assertThat(resolve(kelvin + "=Authorization:API_TOKEN,[api.kafka.com]=Authorization:API_TOKEN"), is(nullValue()));
        assertThat(logger.messages(Level.WARN), contains(containsString("its host is not an ASCII host name"), containsString("its host is not an ASCII host name")));
    }

    @Test
    public void shouldListTheVariablesItReadsByTheEntryPositionEachIsFor() {
        assertThat(ForwardCredentialHeadersByHost.environmentVariables("api.example.com=Authorization:API_TOKEN;X-Tenant:TENANT_ID,http://b.example:8080=X-Api-Key:API_TOKEN"),
            is(Map.of("API_TOKEN", "entry 1 header 1", "TENANT_ID", "entry 1 header 2")));
        assertThat(ForwardCredentialHeadersByHost.environmentVariables(null), is(Map.of()));
    }

    @Test
    public void shouldBeAbsentWhenThePropertyIsEmpty() {
        assertThat(resolve(""), is(nullValue()));
        assertThat(resolve("  "), is(nullValue()));
        assertThat(resolve(null), is(nullValue()));
        assertThat(forConfiguration(configuration().forwardCredentialHeadersByHost("")), is(nullValue()));
        assertThat(logger.entries, is(empty()));
    }

    @Test
    public void shouldNameTheConfiguredHeadersWithoutReadingTheEnvironment() {
        assertThat(ForwardCredentialHeadersByHost.headerNames("a.example=Authorization:NOT_SET;X-Tenant:ALSO_NOT_SET,http://b.example:1=X-Api-Key:K"), contains("authorization", "x-tenant", "x-api-key"));
        assertThat(ForwardCredentialHeadersByHost.headerNames(""), is(empty()));
        assertThat(ForwardCredentialHeadersByHost.headerNames("a.example=Host:K"), is(empty()));
    }

    @Test
    public void shouldOnlyBeAddedByTheForwardClient() {
        Configuration configured = verifiedTls("api.example.com=X-Path:PATH");

        NettyHttpClient forwardClient = new NettyHttpClient(configured, logger, (io.netty.channel.EventLoopGroup) null, null, true);
        NettyHttpClient otherClient = new NettyHttpClient(configured, logger, (io.netty.channel.EventLoopGroup) null, null, false);

        assertThat(forwardClient.forwardCredentialHeaders(), is(notNullValue()));
        assertThat(forwardClient.forwardCredentialHeaders().headersFor("api.example.com", 443, true), hasSize(1));
        assertThat(otherClient.forwardCredentialHeaders(), is(nullValue()));
    }
}

package org.mockserver.mappers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.proxyconfiguration.ForwardTargetBlockedException;
import org.mockserver.proxyconfiguration.InetAddressValidator;
import org.mockserver.proxyconfiguration.MalformedForwardTargetException;
import org.mockserver.proxyconfiguration.ProxyConfiguration;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

/**
 * Through forwardHttpProxy, the host the forward-target checks vet is the host the proxy is sent in the request line:
 * each case asserts both the checks' verdict and the request line MockServer would send the proxy.
 */
public class ForwardHttpProxyRequestTargetTest {

    private static final Map<ProxyConfiguration.Type, ProxyConfiguration> HTTP_PROXY = Collections.singletonMap(
        ProxyConfiguration.Type.HTTP, proxyConfiguration(ProxyConfiguration.Type.HTTP, "127.0.0.1:3128"));

    private final MockServerHttpRequestToFullHttpRequest mapper = new MockServerHttpRequestToFullHttpRequest(new MockServerLogger(), HTTP_PROXY);

    private static Map<String, Configuration> guards() {
        Map<String, Configuration> guards = new LinkedHashMap<>();
        guards.put("forwardProxyAllowedHosts=*.allowed.com", Configuration.configuration().forwardProxyBlockPrivateNetworks(false).forwardProxyAllowedHosts("*.allowed.com").forwardProxyDeniedHosts(""));
        guards.put("forwardProxyDeniedHosts=*.internal.example", Configuration.configuration().forwardProxyBlockPrivateNetworks(false).forwardProxyAllowedHosts("").forwardProxyDeniedHosts("*.internal.example"));
        guards.put("forwardProxyBlockPrivateNetworks=true", Configuration.configuration().forwardProxyBlockPrivateNetworks(true).forwardProxyAllowedHosts("").forwardProxyDeniedHosts(""));
        guards.put("no check", Configuration.configuration().forwardProxyBlockPrivateNetworks(false).forwardProxyAllowedHosts("").forwardProxyDeniedHosts(""));
        return guards;
    }

    @Test
    public void shouldRefuseAHostWhoseTrailingCharactersWouldChangeTheAuthorityTheProxyReads() {
        List<String> hosts = Arrays.asList(
            "169.254.169.254?.8.8.8.8.nip.io",
            "secret.internal.example?.allowed.com",
            "secret.internal.example#.allowed.com",
            "secret.internal.example/.allowed.com",
            "secret.internal.example\\.allowed.com",
            "x@secret.internal.example?.allowed.com",
            "secret.internal.example:80?.allowed.com",
            "169.254.169.254?.allowed.com",
            "secret.internal.example?@8.8.8.8",
            "8.8.8.8:80@169.254.169.254",
            "8.8.8.8@169.254.169.254",
            "8.8.8.8:80/@169.254.169.254",
            "0177.0.0.1",
            "012.0.0.1",
            "0254.020.0.1",
            "2130706433",
            "0x7f000001",
            "0x7f.1",
            "127.1",
            "169.254.43518:80"
        );
        for (Map.Entry<String, Configuration> guard : guards().entrySet()) {
            for (String host : hosts) {
                HttpRequest request = request("/latest/meta-data/").withHeader("Host", host);

                assertThrows(guard.getKey() + " Host: " + host, MalformedForwardTargetException.class,
                    () -> InetAddressValidator.validateForwardTarget(guard.getValue(), request, null, true));
                assertThrows("request line for Host: " + host, MalformedForwardTargetException.class,
                    () -> mapper.getURI(request, HTTP_PROXY));
            }
        }
    }

    @Test
    public void shouldRefuseMoreThanOneHostHeader() {
        HttpRequest twoValues = request("/latest/meta-data/").withHeader("Host", "8.8.8.8", "169.254.169.254");
        HttpRequest twoHeaders = request("/latest/meta-data/").withHeaders(new Header("Host", "api.allowed.com"), new Header("host", "secret.internal.example"));
        for (Map.Entry<String, Configuration> guard : guards().entrySet()) {
            for (HttpRequest request : Arrays.asList(twoValues, twoHeaders)) {
                assertThrows(guard.getKey(), MalformedForwardTargetException.class,
                    () -> InetAddressValidator.validateForwardTarget(guard.getValue(), request, null, true));
                assertThrows(MalformedForwardTargetException.class, () -> mapper.getURI(request, HTTP_PROXY));
            }
        }
    }

    @Test
    public void shouldKeepAPathThatIsNotAbsoluteFromExtendingOrReplacingTheAuthority() {
        Configuration hardened = Configuration.configuration().forwardProxyBlockPrivateNetworks(true).forwardProxyAllowedHosts("").forwardProxyDeniedHosts("*.internal.example");
        Map<String, String> requestLines = new LinkedHashMap<>();
        requestLines.put("HTTP://169.254.169.254/latest/meta-data/", "http://8.8.8.8/HTTP://169.254.169.254/latest/meta-data/");
        requestLines.put("http://169.254.169.254/latest/meta-data/", "http://8.8.8.8/http://169.254.169.254/latest/meta-data/");
        requestLines.put("ftp://files.internal.example/etc/", "http://8.8.8.8/ftp://files.internal.example/etc/");
        requestLines.put("@169.254.169.254/latest/", "http://8.8.8.8/@169.254.169.254/latest/");
        requestLines.put(".internal.example/x", "http://8.8.8.8/.internal.example/x");
        requestLines.put(":8080@169.254.169.254/x", "http://8.8.8.8/:8080@169.254.169.254/x");
        requestLines.put("*", "http://8.8.8.8/*");
        requestLines.put("/ok", "http://8.8.8.8/ok");
        requestLines.put("//double/slash", "http://8.8.8.8//double/slash");
        for (Map.Entry<String, String> requestLine : requestLines.entrySet()) {
            HttpRequest request = request(requestLine.getKey()).withHeader("Host", "8.8.8.8");

            // 8.8.8.8 is the destination the check is given and the destination the proxy is sent
            InetAddressValidator.validateForwardTarget(hardened, request, null, true);
            assertThat(requestLine.getKey(), mapper.getURI(request, HTTP_PROXY), is(requestLine.getValue()));
        }
    }

    @Test
    public void shouldSendOptionsStarWithAnEmptyPath() {
        HttpRequest optionsStar = request("*").withMethod("OPTIONS").withHeader("Host", "8.8.8.8:8080");

        assertThat(mapper.getURI(optionsStar, HTTP_PROXY), is("http://8.8.8.8:8080"));
        assertThat("without a proxy it is sent as it is", mapper.getURI(optionsStar, null), is("*"));
    }

    @Test
    public void shouldPercentEncodeSpacesAndControlCharactersInThePathAndQuery() {
        Map<String, String> requestLines = new LinkedHashMap<>();
        requestLines.put("/a b HTTP/1.1", "http://8.8.8.8/a%20b%20HTTP/1.1");
        requestLines.put(" /x", "http://8.8.8.8/%20/x");
        requestLines.put("/x\u2028y\u0085z\u00a0w\u3000v", "http://8.8.8.8/x%E2%80%A8y%C2%85z%C2%A0w%E3%80%80v");
        requestLines.put("/x\u0000@169.254.169.254/", "http://8.8.8.8/x%00@169.254.169.254/");
        requestLines.put("x\t/y", "http://8.8.8.8/x%09/y");
        requestLines.put("/a\u0001b\u001fc\u007fd", "http://8.8.8.8/a%01b%1Fc%7Fd");
        requestLines.put("/caf\u00e9", "http://8.8.8.8/caf\u00e9");
        for (Map.Entry<String, String> requestLine : requestLines.entrySet()) {
            assertThat(requestLine.getValue(), mapper.getURI(request(requestLine.getKey()).withHeader("Host", "8.8.8.8"), HTTP_PROXY), is(requestLine.getValue()));
        }
        assertThat(mapper.getURI(request("/q?a=\u0000b").withHeader("Host", "8.8.8.8"), HTTP_PROXY), is("http://8.8.8.8/q?a=%00b"));
        assertThat(mapper.getURI(request("/q").withQueryStringParameter("a", "b c").withHeader("Host", "8.8.8.8"), HTTP_PROXY), is("http://8.8.8.8/q?a=b%20c"));
        assertThat("CR and LF are left for the encoder to refuse", mapper.getURI(request("/a\r\nb").withHeader("Host", "8.8.8.8"), HTTP_PROXY), is("http://8.8.8.8/a\r\nb"));
        assertThat("without a proxy the path is sent as it is", mapper.getURI(request("/x\u0000y"), null), is("/x\u0000y"));
    }

    @Test
    public void shouldGiveABareQueryALeadingSlash() {
        HttpRequest bareQuery = request("").withQueryStringParameter("a", "b").withHeader("Host", "8.8.8.8");

        assertThat(mapper.getURI(bareQuery, HTTP_PROXY), is("http://8.8.8.8/?a=b"));
    }

    @Test
    public void shouldGiveAPathTheProxyIsSentWithoutAuthorityALeadingSlashToo() {
        HttpRequest noDestination = request("HTTP://169.254.169.254/latest/meta-data/");

        assertThat(mapper.getURI(noDestination, HTTP_PROXY), is("/HTTP://169.254.169.254/latest/meta-data/"));
        assertThat("without a proxy the path is sent as it is", mapper.getURI(noDestination, null), is("HTTP://169.254.169.254/latest/meta-data/"));
    }

    @Test
    public void shouldCheckTheRemoteAddressTheProxyIsSentWhenThereIsNoHostHeader() {
        Configuration blockPrivateNetworks = Configuration.configuration().forwardProxyBlockPrivateNetworks(true).forwardProxyAllowedHosts("").forwardProxyDeniedHosts("");
        HttpRequest metadata = request("/latest/meta-data/").withRemoteAddress("169.254.169.254:5000");
        HttpRequest publicAddress = request("/latest/meta-data/").withRemoteAddress("8.8.8.8:5000");
        HttpRequest unreadable = request("/latest/meta-data/").withRemoteAddress("8.8.8.8:5000@169.254.169.254");

        assertThrows(ForwardTargetBlockedException.class, () -> InetAddressValidator.validateForwardTarget(blockPrivateNetworks, metadata, null, true));
        assertThat(mapper.getURI(metadata, HTTP_PROXY), is("http://169.254.169.254:5000/latest/meta-data/"));
        InetAddressValidator.validateForwardTarget(blockPrivateNetworks, publicAddress, null, true);
        assertThat(mapper.getURI(publicAddress, HTTP_PROXY), is("http://8.8.8.8:5000/latest/meta-data/"));
        assertThrows(ForwardTargetBlockedException.class, () -> InetAddressValidator.validateForwardTarget(blockPrivateNetworks, unreadable, null, true));
        assertThrows(ForwardTargetBlockedException.class, () -> mapper.getURI(unreadable, HTTP_PROXY));
    }

    @Test
    public void shouldSendAWellFormedHostAsItWasGiven() {
        Configuration allowList = Configuration.configuration().forwardProxyBlockPrivateNetworks(false).forwardProxyAllowedHosts("*.allowed.com").forwardProxyDeniedHosts("");
        Map<String, String> requestLines = new LinkedHashMap<>();
        requestLines.put("api.allowed.com", "http://api.allowed.com/path?q=1");
        requestLines.put("API.Allowed.com:8080", "http://API.Allowed.com:8080/path?q=1");
        for (Map.Entry<String, String> requestLine : requestLines.entrySet()) {
            HttpRequest request = request("/path").withQueryStringParameter("q", "1").withHeader("Host", requestLine.getKey());

            InetAddressValidator.validateForwardTarget(allowList, request, null, true);
            assertThat(mapper.getURI(request, HTTP_PROXY), is(requestLine.getValue()));
        }
        assertThrows(ForwardTargetBlockedException.class,
            () -> InetAddressValidator.validateForwardTarget(allowList, request("/path").withHeader("Host", "api.other.com"), null, true));
        HttpRequest ipv6 = request("/path").withHeader("Host", "[2001:db8::1]:8080");
        InetAddressValidator.validateForwardTarget(guards().get("no check"), ipv6, null, true);
        assertThat(mapper.getURI(ipv6, HTTP_PROXY), is("http://[2001:db8::1]:8080/path"));
    }

    @Test
    public void shouldLeaveARequestSentWithoutForwardHttpProxyAsItWas() {
        HttpRequest request = request("@169.254.169.254/latest/").withHeader("Host", "8.8.8.8?x");

        assertThat(mapper.getURI(request, null), is("@169.254.169.254/latest/"));
        assertThat(mapper.getURI(request("@169.254.169.254/latest/").withHeader("Host", "8.8.8.8?x").withSecure(true), HTTP_PROXY), is("@169.254.169.254/latest/"));
    }
}

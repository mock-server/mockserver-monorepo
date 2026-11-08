package org.mockserver.proxyconfiguration;

import org.junit.Test;
import org.mockserver.model.Header;
import org.mockserver.model.SocketAddress;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;

/**
 * The destination a forwarded request names is read only from a Host, socket address or remote address that is exactly
 * one host or host:port, so an upstream proxy cannot read it as a host other than the one the forward-target checks
 * are given.
 */
public class HttpProxyRequestTargetTest {

    @Test
    public void shouldReadAHostOrHostAndPortAsItIs() {
        assertAuthority("example.com", "example.com", "example.com");
        assertAuthority("Example.COM:8080", "Example.COM:8080", "Example.COM");
        assertAuthority("8.8.8.8", "8.8.8.8", "8.8.8.8");
        assertAuthority("8.8.8.8:80", "8.8.8.8:80", "8.8.8.8");
        assertAuthority("my_service-1.local:65535", "my_service-1.local:65535", "my_service-1.local");
        assertAuthority("example.com.", "example.com.", "example.com.");
        assertAuthority("[::1]", "[::1]", "::1");
        assertAuthority("[2001:db8::1]:8443", "[2001:db8::1]:8443", "2001:db8::1");
        assertAuthority("[::ffff:169.254.169.254]:80", "[::ffff:169.254.169.254]:80", "::ffff:169.254.169.254");
        assertAuthority("[::10.0.0.1]:80", "[::10.0.0.1]:80", "::10.0.0.1");
        assertAuthority("0.0.0.0", "0.0.0.0", "0.0.0.0");
        assertAuthority("255.255.255.255:8080", "255.255.255.255:8080", "255.255.255.255");
        assertAuthority("10.0.0.1", "10.0.0.1", "10.0.0.1");
        assertAuthority("123.example.com", "123.example.com", "123.example.com");
        assertAuthority("0x7f.example.com", "0x7f.example.com", "0x7f.example.com");
    }

    @Test
    public void shouldReadAHostWhoseLastLabelIsNumericOrHexAsAnIpv4AddressAcceptedOnlyAsFourDecimalOctets() {
        for (String ambiguous : new String[]{"0177.0.0.1", "012.0.0.1", "127.000.000.001", "2130706433", "0x7f000001", "0x7f.1", "127.1", "1.2.3.4.", "256.1.1.1", "1.2.3.4.5", "example.123", "example.0x1", "0x", "1..2.3"}) {
            assertThat(ambiguous, HttpProxyRequestTarget.isAmbiguousIpv4(ambiguous), is(true));
            assertThat(ambiguous, HttpProxyRequestTarget.isHost(ambiguous), is(false));
        }
        for (String unambiguous : new String[]{"1.2.3.4", "0.0.0.0", "255.255.255.255", "example.com", "example.com.", "123.example.com", "a.0b1", "::1", "64:ff9b::7f00:1"}) {
            assertThat(unambiguous, HttpProxyRequestTarget.isAmbiguousIpv4(unambiguous), is(false));
        }
    }

    @Test
    public void shouldReadTheHostARequestIsSentToFromItsRemoteAddressSocketAddressOrHostHeader() {
        assertThat(HttpProxyRequestTarget.destinationHost(request("/").withHeader("Host", "ignored.example"), InetSocketAddress.createUnresolved("api.example.com", 443)), is("api.example.com"));
        assertThat(HttpProxyRequestTarget.destinationHost(request("/").withHeader("Host", "ignored.example").withSocketAddress("api.example.com", 443, SocketAddress.Scheme.HTTPS), null), is("api.example.com"));
        assertThat(HttpProxyRequestTarget.destinationHost(request("/").withHeader("Host", "api.example.com:8443"), null), is("api.example.com"));
        assertThat(HttpProxyRequestTarget.destinationHost(request("/").withHeader("Host", "[2001:db8::1]:8443"), null), is("2001:db8::1"));
        assertThat(HttpProxyRequestTarget.destinationHost(request("/"), new InetSocketAddress(InetAddress.getLoopbackAddress(), 80)), is(InetAddress.getLoopbackAddress().getHostName()));
        assertThat(HttpProxyRequestTarget.destinationHost(request("/"), null), is(nullValue()));
    }

    @Test
    public void shouldRefuseTheHostARequestIsSentToWhenItIsNotOneHost() {
        for (String host : new String[]{"secret.internal.example?.allowed.com", "user@secret.internal.example", "secret.internal.example/.allowed.com", "0177.0.0.1", "api.example.com:443:443", "api.example.com:abc"}) {
            assertThrows(host, MalformedForwardTargetException.class,
                () -> HttpProxyRequestTarget.destinationHost(request("/").withHeader("Host", host), null));
        }
        for (String host : new String[]{"secret.internal.example?.allowed.com", "user@secret.internal.example", "0177.0.0.1", "fe80::1%eth0"}) {
            assertThrows(host, MalformedForwardTargetException.class,
                () -> HttpProxyRequestTarget.destinationHost(request("/").withSocketAddress(host, 443, SocketAddress.Scheme.HTTPS), null));
            assertThrows(host, MalformedForwardTargetException.class,
                () -> HttpProxyRequestTarget.destinationHost(request("/"), InetSocketAddress.createUnresolved(host, 443)));
        }
    }

    @Test
    public void shouldRefuseATunnelDestinationThatIsNotOneHost() {
        for (String host : new String[]{"user@secret.internal.example", "secret.internal.example/.allowed.com", "secret.internal.example?.allowed.com", "db.example.com:5432", "0177.0.0.1", "127.1", "fe80::1%en0", "[fe80::1%25en0]", "::ffff:017.0.0.1", "ex\u00e4mple.com", ""}) {
            InetSocketAddress destination = InetSocketAddress.createUnresolved(host, 5432);
            MalformedForwardTargetException refused = assertThrows(host, MalformedForwardTargetException.class, () -> HttpProxyRequestTarget.requireTunnelDestination(destination));
            assertThat(refused.getMessage(), containsString("not exactly one host"));
        }
        for (String host : new String[]{"db.example.com", "DB_1.example.com.", "10.0.0.5", "::1", "[2001:db8::1]", "::ffff:10.0.0.5"}) {
            InetSocketAddress destination = InetSocketAddress.createUnresolved(host, 5432);
            assertThat(host, HttpProxyRequestTarget.requireTunnelDestination(destination), is(destination));
        }
        InetSocketAddress resolved = new InetSocketAddress(InetAddress.getLoopbackAddress(), 5432);
        assertThat(HttpProxyRequestTarget.requireTunnelDestination(resolved), is(resolved));
    }

    @Test
    public void shouldNotReadAnythingButExactlyOneHostOrHostAndPort() {
        List<String> notAuthorities = Arrays.asList(
            // what follows the host would end the authority a proxy reads, or become it
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
            // user info, path, query, fragment, whitespace, control and non-ASCII characters
            "user:pass@example.com",
            "example.com/path",
            "example.com?query",
            "example.com#fragment",
            "example.com:80/path",
            "example .com",
            " example.com",
            "example.com ",
            "example.com\t",
            "example.com\r\nX-Injected: 1",
            "example.com\u0000",
            "ex\u00e4mple.com",
            "example.com%2f",
            // a port that is not 1 to 65535
            "example.com:",
            "example.com:0",
            "example.com:65536",
            "example.com:123456",
            "example.com:80a",
            "example.com:-1",
            "example.com:80:80",
            "example.com:080",
            ":80",
            // an IPv6 literal without brackets, with a zone, or not one at all
            "::1",
            "2001:db8::1",
            "[::1",
            "[]",
            "[::1]x",
            "[::1]:",
            "[::1]:0",
            "[fe80::1%25eth0]",
            "[not-an-address]",
            "[::1]@169.254.169.254",
            // an IPv6 literal whose IPv4 tail is not four decimal octets without leading zeros
            "[::ffff:017.0.0.1]",
            "[::ffff:127.000.0.1]:80",
            "[64:ff9b::0169.254.169.254]",
            // a name with an empty label
            "a..b",
            ".example.com",
            "1.2.3.4..",
            "example.com..:80",
            // a numeric IPv4 address that is not four decimal octets without leading zeros
            "0177.0.0.1",
            "012.0.0.1:80",
            "0254.020.0.1",
            "127.000.000.001",
            "2130706433",
            "0x7f000001",
            "0X7F.1",
            "0x7f.0.0.1",
            "127.1",
            "169.254.43518",
            "1.2.3.4.",
            "256.1.1.1",
            "1.2.3.4.5",
            "service.123",
            "service.0x1"
        );
        for (String notAuthority : notAuthorities) {
            assertThat(notAuthority, HttpProxyRequestTarget.parse(notAuthority), is(nullValue()));
        }
    }

    @Test
    public void shouldTakeTheDestinationFromTheHostHeaderOrElseTheRemoteAddress() {
        assertThat(HttpProxyRequestTarget.of(request("/").withHeader("Host", "example.com:8080").withRemoteAddress("127.0.0.1:5000")).authority(), is("example.com:8080"));
        assertThat(HttpProxyRequestTarget.of(request("/").withRemoteAddress("203.0.113.10:5000")).authority(), is("203.0.113.10:5000"));
        assertThat(HttpProxyRequestTarget.of(request("/").withHeader("Host", "").withRemoteAddress("203.0.113.10:5000")).authority(), is("203.0.113.10:5000"));
        assertThat(HttpProxyRequestTarget.of(request("/")), is(nullValue()));
    }

    @Test
    public void shouldRefuseARequestWhoseHostOrRemoteAddressIsNotOneHostOrHostAndPort() {
        ForwardTargetBlockedException badHost = assertThrows(MalformedForwardTargetException.class,
            () -> HttpProxyRequestTarget.of(request("/").withHeader("Host", "169.254.169.254?.8.8.8.8.nip.io")));
        assertThat(badHost.getMessage(), containsString("not exactly one host or host:port"));
        assertThrows(ForwardTargetBlockedException.class,
            () -> HttpProxyRequestTarget.of(request("/").withRemoteAddress("hostname/169.254.169.254:5000")));
    }

    @Test
    public void shouldRefuseARequestWithMoreThanOneHostHeader() {
        ForwardTargetBlockedException twoValues = assertThrows(MalformedForwardTargetException.class,
            () -> HttpProxyRequestTarget.of(request("/").withHeader("Host", "8.8.8.8", "169.254.169.254")));
        assertThat(twoValues.getMessage(), containsString("2 Host headers"));
        assertThrows(ForwardTargetBlockedException.class,
            () -> HttpProxyRequestTarget.of(request("/").withHeaders(new Header("Host", "8.8.8.8"), new Header("host", "169.254.169.254"))));
    }

    private static void assertAuthority(String value, String authority, String host) {
        HttpProxyRequestTarget.Authority parsed = HttpProxyRequestTarget.parse(value);
        assertThat(value, parsed.authority(), is(authority));
        assertThat(value, parsed.host(), is(host));
    }
}

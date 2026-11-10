package org.mockserver.proxyconfiguration;

import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThrows;

public class ProxyProtocolTrustedSourcesTest {

    @Test
    public void shouldTrustAnySourceWhenTheListHasNoEntry() {
        assertThat(ProxyProtocolTrustedSources.parse(null), nullValue());
        assertThat(ProxyProtocolTrustedSources.parse(""), nullValue());
        assertThat(ProxyProtocolTrustedSources.parse("  "), nullValue());
    }

    @Test
    public void shouldRefuseAValueThatListsNoEntry() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> ProxyProtocolTrustedSources.parse(" , ,"));

        assertThat(refused.getMessage(), containsString("lists no IP address or CIDR block"));
    }

    @Test
    public void shouldDescribeTheEntriesInForce() {
        assertThat(String.valueOf(ProxyProtocolTrustedSources.parse(" 10.0.1.0/24 ,, fd00:1::/64")), is("10.0.1.0/24, fd00:1::/64"));
    }

    @Test
    public void shouldTrustOnlyListedIpv4AddressesAndBlocks() throws UnknownHostException {
        ProxyProtocolTrustedSources sources = ProxyProtocolTrustedSources.parse("10.0.0.0/8, 192.168.1.5");

        assertThat(sources, notNullValue());
        assertThat(sources.trusts(peer("10.1.2.3")), is(true));
        assertThat(sources.trusts(peer("192.168.1.5")), is(true));
        assertThat(sources.trusts(peer("192.168.1.6")), is(false));
        assertThat(sources.trusts(peer("11.0.0.1")), is(false));
        assertThat(sources.trusts(peer("::1")), is(false));
    }

    @Test
    public void shouldTrustOnlyListedIpv6AddressesAndBlocks() throws UnknownHostException {
        ProxyProtocolTrustedSources sources = ProxyProtocolTrustedSources.parse("fd00::/8,[::1]");

        assertThat(sources, notNullValue());
        assertThat(sources.trusts(peer("fd12:3456::5")), is(true));
        assertThat(sources.trusts(peer("::1")), is(true));
        assertThat(sources.trusts(peer("fe80::1")), is(false));
        assertThat(sources.trusts(peer("10.1.2.3")), is(false));
    }

    @Test
    public void shouldMatchAnIpv4MappedPeerAsTheIpv4AddressItCarries() throws UnknownHostException {
        ProxyProtocolTrustedSources sources = ProxyProtocolTrustedSources.parse("10.0.0.0/8");
        byte[] mapped = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 10, 1, 2, 3};

        assertThat(sources, notNullValue());
        assertThat(sources.trusts(new InetSocketAddress(InetAddress.getByAddress(mapped), 5000)), is(true));
    }

    @Test
    public void shouldNotTrustAPeerThatIsNotAnIpSocket() {
        ProxyProtocolTrustedSources sources = ProxyProtocolTrustedSources.parse("0.0.0.0/1, 128.0.0.0/1");

        assertThat(sources, notNullValue());
        assertThat(sources.trusts(null), is(false));
        assertThat(sources.trusts(InetSocketAddress.createUnresolved("lb.example.com", 5000)), is(false));
        assertThat(sources.trusts(new SocketAddress() {
        }), is(false));
    }

    @Test
    public void shouldRefuseAnEntryWiderThanItLooks() {
        assertRefused("0.0.0.0/0", "covers every address");
        assertRefused("::/0", "covers every address");
        assertRefused("::/64", "covers every IPv4 address");
        assertRefused("::ffff:10.0.0.1/64", "covers every IPv4 address");
        assertRefused("::ffff:0:0/96", "covers every IPv4 address");
        assertRefused("::ffff:0.0.0.0/96", "covers every IPv4 address");
        assertRefused("::0.0.0.0/96", "IPv4-compatible");
        assertRefused("::10.0.1.17", "IPv4-compatible");
        assertRefused("10.1.2.3/8", "has bits set after its prefix length");
        assertRefused("fd00::1/8", "has bits set after its prefix length");
        assertRefused("fe80::1%eth0", "has a zone ID");
        assertRefused("010.0.1.17", "has an IPv4 part with a leading zero");
    }

    @Test
    public void shouldAcceptAnIpv4MappedEntryAsTheIpv4BlockItCarries() throws UnknownHostException {
        ProxyProtocolTrustedSources sources = ProxyProtocolTrustedSources.parse("::ffff:10.0.1.0/120");

        assertThat(sources, notNullValue());
        assertThat(sources.trusts(peer("10.0.1.200")), is(true));
        assertThat(sources.trusts(peer("10.0.2.1")), is(false));
        ProxyProtocolTrustedSources narrowest = ProxyProtocolTrustedSources.parse("::ffff:0:0/97, 0:0:0:0:0:FFFF:10.0.1.17");
        assertThat(narrowest, notNullValue());
        assertThat(narrowest.trusts(peer("1.2.3.4")), is(true));
        assertThat(narrowest.trusts(peer("10.0.1.17")), is(true));
        assertThat(narrowest.trusts(peer("200.0.0.1")), is(false));
    }

    private static void assertRefused(String entry, String reason) {
        IllegalArgumentException refused = assertThrows(entry, IllegalArgumentException.class, () -> ProxyProtocolTrustedSources.parse("10.0.1.0/24," + entry));
        assertThat(refused.getMessage(), startsWith("proxyProtocolTrustedSources entry \"" + entry + "\" "));
        assertThat(entry, refused.getMessage(), containsString(reason));
    }

    @Test
    public void shouldRefuseAnEntryThatIsNotAnIpAddressOrCidrBlock() {
        for (String invalid : new String[]{"lb.example.com", "!10.0.0.0/8", "?10.0.0.1", "10.0.0.0/33", "fd00::/129", "10.0.0.1:80", "10.0.0.0/", "*"}) {
            IllegalArgumentException refused = assertThrows(invalid, IllegalArgumentException.class, () -> ProxyProtocolTrustedSources.parse("10.0.0.0/8," + invalid));
            assertThat(refused.getMessage(), containsString("proxyProtocolTrustedSources entry \"" + invalid + "\" is not an IP address or CIDR block"));
        }
    }

    private static InetSocketAddress peer(String address) throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getByName(address), 5000);
    }
}

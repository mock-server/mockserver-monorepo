package org.mockserver.proxyconfiguration;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThrows;

public class ForwardTargetHostPolicyTest {

    private static ForwardTargetHostPolicy policy(String allowed, String denied) {
        return ForwardTargetHostPolicy.reuseOrParse(null, allowed, denied);
    }

    private static boolean permits(ForwardTargetHostPolicy policy, String host, String address) throws UnknownHostException {
        try {
            policy.check(host, address != null ? InetAddress.getByAddress(host, InetAddress.getByName(address).getAddress()) : null);
            return true;
        } catch (ForwardTargetBlockedException refused) {
            return false;
        }
    }

    @Test
    public void shouldHaveNoPolicyWhenNeitherListIsSet() {
        assertThat(policy("", ""), nullValue());
        assertThat(policy(null, null), nullValue());
        assertThat(policy(" , ", ""), nullValue());
    }

    @Test
    public void shouldParseTheListsOnceWhileTheyAreUnchanged() {
        ForwardTargetHostPolicy first = policy("api.example.com", "10.0.0.0/8");

        assertThat(ForwardTargetHostPolicy.reuseOrParse(first, "api.example.com", "10.0.0.0/8"), sameInstance(first));
        assertThat(ForwardTargetHostPolicy.reuseOrParse(null, "api.example.com", "10.0.0.0/8"), sameInstance(first));
        assertThat(ForwardTargetHostPolicy.reuseOrParse(first, "other.example.com", "10.0.0.0/8"), is(notNullValue()));
    }

    @Test
    public void shouldMatchExactNamesIgnoringCaseAndATrailingDot() throws UnknownHostException {
        ForwardTargetHostPolicy denied = policy("", "Internal.Example.com");

        assertThat(permits(denied, "internal.example.com", null), is(false));
        assertThat(permits(denied, "INTERNAL.EXAMPLE.COM.", null), is(false));
        assertThat(permits(denied, "other.example.com", null), is(true));
        assertThat(permits(denied, "notinternal.example.com", null), is(true));
    }

    @Test
    public void shouldMatchAWildcardSuffixAndTheDomainItself() throws UnknownHostException {
        ForwardTargetHostPolicy allowed = policy("*.example.com", "");

        assertThat(permits(allowed, "api.example.com", null), is(true));
        assertThat(permits(allowed, "a.b.example.com", null), is(true));
        assertThat(permits(allowed, "example.com", null), is(true));
        assertThat(permits(allowed, "evilexample.com", null), is(false));
        assertThat(permits(allowed, "example.com.evil.net", null), is(false));
    }

    @Test
    public void shouldMatchCidrBlocksAgainstTheResolvedAddress() throws UnknownHostException {
        ForwardTargetHostPolicy denied = policy("", "10.0.0.0/8, 192.168.1.128/25, fd00::/8");

        assertThat(permits(denied, "service.local", "10.20.30.40"), is(false));
        assertThat(permits(denied, "service.local", "192.168.1.200"), is(false));
        assertThat(permits(denied, "service.local", "192.168.1.100"), is(true));
        assertThat(permits(denied, "service.local", "fd12::1"), is(false));
        assertThat(permits(denied, "service.local", "11.0.0.1"), is(true));
        assertThat(permits(denied, "10.1.1.1", null), is(false));
        assertThat(permits(denied, "[fd00::5]", null), is(false));
    }

    @Test
    public void shouldMatchAnIpv4MappedAddressAsTheIpv4Address() throws UnknownHostException {
        ForwardTargetHostPolicy denied = policy("", "::ffff:10.0.0.0/104");

        assertThat(permits(denied, "::ffff:10.1.2.3", null), is(false));
        assertThat(permits(denied, "10.1.2.3", null), is(false));
        assertThat(permits(denied, "11.1.2.3", null), is(true));
    }

    @Test
    public void shouldMatchAnIpv4CompatibleAddressAsTheIpv4Address() throws UnknownHostException {
        ForwardTargetHostPolicy denied = policy("", "10.0.0.0/8");

        assertThat(permits(denied, "::10.1.2.3", null), is(false));
        assertThat(permits(denied, "service.local", "::10.1.2.3"), is(false));
        assertThat(permits(policy("", "::/96"), "::10.1.2.3", null), is(false));
        assertThat(permits(policy("", "::ffff:0:0/96"), "10.1.2.3", null), is(false));
        assertThat(permits(policy("::/0", ""), "10.1.2.3", null), is(false));
    }

    @Test
    public void shouldMatchANat64OrIpv4TranslatedAddressAsTheIpv4AddressItCarries() throws UnknownHostException {
        ForwardTargetHostPolicy denied = policy("", "169.254.0.0/16");
        for (String metadata : new String[]{"64:ff9b::a9fe:a9fe", "[64:ff9b::a9fe:a9fe]", "64:ff9b:1::a9fe:a9fe", "64:ff9b:1:a9fe:a9:fe00::", "::ffff:0:a9fe:a9fe"}) {
            assertThat(metadata, permits(denied, metadata, null), is(false));
        }
        assertThat(permits(denied, "service.local", "64:ff9b::a9fe:a9fe"), is(false));
        assertThat(permits(denied, "64:ff9b::808:808", null), is(true));
        assertThat(permits(policy("169.254.0.0/16", ""), "64:ff9b::a9fe:a9fe", null), is(true));
        assertThat(permits(policy("169.254.0.0/16", ""), "64:ff9b::808:808", null), is(false));
    }

    @Test
    public void shouldDenyALocalUseNat64Or6to4AddressWhenAnyIpv4AddressItMayCarryIsDeniedButNeverAllowOneForIt() throws UnknownHostException {
        ForwardTargetHostPolicy denied = policy("", "169.254.0.0/16,10.0.0.0/8");
        for (String host : new String[]{"64:ff9b:1:1::a9fe:a9fe", "64:ff9b:1:abcd::a9fe:a9fe", "64:ff9b:1:120a:0:1::", "2002:a9fe:a9fe::1", "2002:a00:1::1"}) {
            assertThat(host, permits(denied, host, null), is(false));
        }
        assertThat(permits(denied, "64:ff9b:1:abcd::808:808", null), is(true));
        assertThat(permits(denied, "2002:808:808::1", null), is(true));
        ForwardTargetHostPolicy loopbackDenied = policy("", "127.0.0.0/8");
        assertThat(permits(loopbackDenied, "64:ff9b:1:abcd:7f:0:100:0", null), is(false));
        assertThat("a zero placement is not read as 0.0.0.0", permits(loopbackDenied, "64:ff9b:1::808:808", null), is(true));
        for (String host : new String[]{"64:ff9b:1::a9fe:a9fe", "64:ff9b:1:a9fe:a9:fe00::", "2002:a9fe:a9fe::1"}) {
            assertThat(host, permits(policy("169.254.0.0/16", ""), host, null), is(false));
        }
        assertThat(permits(policy("64:ff9b:1::/48", ""), "64:ff9b:1::a9fe:a9fe", null), is(true));
    }

    @Test
    public void shouldKeepANat64EntryAnIpv6BlockRatherThanEveryIpv4Address() throws UnknownHostException {
        assertThat(permits(policy("", "64:ff9b::/96"), "64:ff9b::808:808", null), is(false));
        assertThat(permits(policy("", "64:ff9b::/96"), "8.8.8.8", null), is(true));
        assertThat(permits(policy("64:ff9b::/96", ""), "64:ff9b::808:808", null), is(true));
        assertThat(permits(policy("64:ff9b::/96", ""), "8.8.8.8", null), is(false));
        assertThat("a NAT64 block shorter than /96 is a valid IPv6 block", permits(policy("", "64:ff9b::/32"), "64:ff9b::a00:1", null), is(false));
    }

    @Test
    public void shouldRefuseEverySpellingOfThisHostWhenALoopbackAddressIsDenied() throws UnknownHostException {
        for (String loopbackDeny : new String[]{"127.0.0.0/8", "::1/128", "127.0.0.1"}) {
            ForwardTargetHostPolicy denied = policy("", loopbackDeny);
            for (String thisHost : new String[]{"127.0.0.1", "127.9.9.9", "::1", "[::1]", "::127.0.0.1", "::ffff:127.0.0.1", "0.0.0.0", "::", "[::]"}) {
                assertThat(loopbackDeny + " / " + thisHost, permits(denied, thisHost, null), is(false));
            }
            assertThat(permits(denied, "service.local", "0.0.0.0"), is(false));
            assertThat(permits(denied, "8.8.8.8", null), is(true));
        }
        assertThat(permits(policy("", "10.0.0.0/8"), "0.0.0.0", null), is(true));
        assertThat(permits(policy("127.0.0.1", ""), "0.0.0.0", null), is(false));
    }

    @Test
    public void shouldMatchASingleAddress() throws UnknownHostException {
        ForwardTargetHostPolicy allowed = policy("203.0.113.7", "");

        assertThat(permits(allowed, "203.0.113.7", null), is(true));
        assertThat(permits(allowed, "api.example.com", "203.0.113.7"), is(true));
        assertThat(permits(allowed, "api.example.com", "203.0.113.8"), is(false));
    }

    @Test
    public void shouldLetTheDenyListWin() throws UnknownHostException {
        ForwardTargetHostPolicy both = policy("*.example.com", "admin.example.com");

        assertThat(permits(both, "api.example.com", null), is(true));
        assertThat(permits(both, "admin.example.com", null), is(false));
        assertThat(permits(both, "api.other.com", null), is(false));
    }

    @Test
    public void shouldOnlyNeedAddressesWhenAListHasAddresses() {
        assertThat(policy("*.example.com", "admin.example.com").matchesAddresses(), is(false));
        assertThat(policy("*.example.com", "10.0.0.0/8").matchesAddresses(), is(true));
    }

    @Test
    public void shouldRejectInvalidEntries() {
        for (String invalid : new String[]{"10.0.0.0/33", "10.0.0.0/x", "not-an-ip/8", "foo*.example.com", "*.", "a b", "http://example.com", "127.1", "2130706433", "10.0.0"}) {
            IllegalArgumentException refused = assertThrows(invalid, IllegalArgumentException.class,
                () -> ForwardTargetHostPolicy.validate("forwardProxyDeniedHosts", invalid));
            assertThat(refused.getMessage(), containsString("forwardProxyDeniedHosts"));
        }
    }

    @Test
    public void shouldRefuseEveryTargetWhenAListCannotBeParsed() throws UnknownHostException {
        ForwardTargetHostPolicy invalid = policy("", "10.0.0.0/99");

        ForwardTargetBlockedException refused = assertThrows(ForwardTargetBlockedException.class, () -> invalid.check("api.example.com", null));
        assertThat(refused.getMessage(), containsString("10.0.0.0/99"));
        assertThat(permits(invalid, "anything.example.com", "8.8.8.8"), is(false));
    }
}

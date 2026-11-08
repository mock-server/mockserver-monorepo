package org.mockserver.proxyconfiguration;

import io.netty.util.NetUtil;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The parsed {@code forwardProxyAllowedHosts} and {@code forwardProxyDeniedHosts} lists. Each is comma-separated; an
 * entry is a host name, matched exactly; a domain suffix ({@code *.example.com} or {@code .example.com}), which matches
 * the domain and every name under it; an IP address; or a CIDR block ({@code 10.0.0.0/8}, {@code fd00::/8}). Names
 * are matched without case and without a trailing dot. Addresses and CIDR blocks match the address a target resolves
 * to, as well as a target given as an address. A target on the deny list is refused; when an allow list is set, a
 * target on neither the name nor the address part of it is refused too.
 * <p>
 * Parsed once per distinct pair of lists, never per request: {@link #reuseOrParse} returns the previous policy while
 * the lists are unchanged. A list that cannot be parsed makes the policy refuse every target, rather than drop the
 * entry and widen what is allowed.
 */
public final class ForwardTargetHostPolicy {

    private static volatile ForwardTargetHostPolicy lastParsed;

    private final String allowedSource;
    private final String deniedSource;
    @Nullable
    private final HostList allowed;
    @Nullable
    private final HostList denied;
    @Nullable
    private final String invalid;

    private ForwardTargetHostPolicy(String allowedSource, String deniedSource) {
        this.allowedSource = allowedSource;
        this.deniedSource = deniedSource;
        HostList allowedList = null;
        HostList deniedList = null;
        String invalidReason = null;
        try {
            allowedList = HostList.parse("forwardProxyAllowedHosts", allowedSource);
            deniedList = HostList.parse("forwardProxyDeniedHosts", deniedSource);
        } catch (IllegalArgumentException e) {
            invalidReason = e.getMessage();
        }
        this.allowed = allowedList;
        this.denied = deniedList;
        this.invalid = invalidReason;
    }

    /**
     * @return the policy for these lists, or null when both are blank, so callers skip the check entirely; the
     * {@code previous} policy (or the one parsed last) when it was parsed from the same lists
     */
    @Nullable
    public static ForwardTargetHostPolicy reuseOrParse(@Nullable ForwardTargetHostPolicy previous, @Nullable String allowedHosts, @Nullable String deniedHosts) {
        String allowedSource = allowedHosts != null ? allowedHosts : "";
        String deniedSource = deniedHosts != null ? deniedHosts : "";
        if (allowedSource.isEmpty() && deniedSource.isEmpty()) {
            return null;
        }
        if (previous != null && previous.parsedFrom(allowedSource, deniedSource)) {
            return previous;
        }
        ForwardTargetHostPolicy last = lastParsed;
        if (last != null && last.parsedFrom(allowedSource, deniedSource)) {
            return last.isEmpty() ? null : last;
        }
        ForwardTargetHostPolicy parsed = new ForwardTargetHostPolicy(allowedSource, deniedSource);
        lastParsed = parsed;
        return parsed.isEmpty() ? null : parsed;
    }

    /**
     * @throws IllegalArgumentException naming the entry when the list cannot be parsed
     */
    public static void validate(String propertyName, @Nullable String hosts) {
        HostList.parse(propertyName, hosts != null ? hosts : "");
    }

    /**
     * Policies parsed from the same lists are equal, so a policy can key a cache.
     */
    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof ForwardTargetHostPolicy && ((ForwardTargetHostPolicy) other).parsedFrom(allowedSource, deniedSource));
    }

    @Override
    public int hashCode() {
        return 31 * allowedSource.hashCode() + deniedSource.hashCode();
    }

    private boolean parsedFrom(String allowedSource, String deniedSource) {
        return this.allowedSource.equals(allowedSource) && this.deniedSource.equals(deniedSource);
    }

    private boolean isEmpty() {
        return invalid == null && allowed == null && denied == null;
    }

    /**
     * Whether a target's resolved address can change the outcome, so it is worth looking the name up for.
     */
    boolean matchesAddresses() {
        return invalid != null || (allowed != null && allowed.hasAddresses()) || (denied != null && denied.hasAddresses());
    }

    /**
     * @param requestedHost the target as given, a name or an address literal
     * @param address       the address it resolved to, or null when it was not looked up
     * @throws ForwardTargetBlockedException when the target is denied or not allowed
     */
    void check(String requestedHost, @Nullable InetAddress address) {
        if (invalid != null) {
            throw new ForwardTargetBlockedException("Forward to " + requestedHost + " refused because " + invalid);
        }
        String name = normaliseName(requestedHost);
        byte[] bytes = address != null ? address.getAddress() : NetUtil.createByteArrayFromIpAddressString(name);
        if (denied != null && (denied.matchesName(name) || denied.denies(bytes) || denied.deniesAnyPossibleIpv4(bytes))) {
            throw new ForwardTargetBlockedException("Forward to host denied by forwardProxyDeniedHosts: " + describe(requestedHost, address));
        }
        if (allowed != null && !allowed.matchesName(name) && !allowed.matchesAddress(bytes)) {
            throw new ForwardTargetBlockedException("Forward to host not in forwardProxyAllowedHosts: " + describe(requestedHost, address));
        }
    }

    private static String describe(String requestedHost, @Nullable InetAddress address) {
        if (address == null || address.getHostAddress().equals(stripBrackets(requestedHost))) {
            return requestedHost;
        }
        return requestedHost + " (" + address.getHostAddress() + ")";
    }

    private static String normaliseName(String host) {
        String name = stripBrackets(host);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static String stripBrackets(String host) {
        if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * An address is compared as the IPv4 address it carries, when it carries one ({@link EmbeddedIpv4#of}): IPv4-mapped,
     * IPv4-compatible, IPv4-translated or well-known NAT64; {@code ::} and {@code ::1} are not IPv4-compatible.
     */
    private static byte[] canonical(byte[] address) {
        byte[] ipv4 = EmbeddedIpv4.of(address, true);
        return ipv4 != null ? ipv4 : address;
    }

    /**
     * An address or block in a list is read as IPv4 only when it is written IPv4-mapped or IPv4-compatible, which is
     * the IPv4 address itself; a NAT64 or IPv4-translated entry stays an IPv6 block, so {@code 64:ff9b::/96} does not
     * stand for every IPv4 address.
     */
    private static byte[] canonicalEntry(byte[] literal) {
        byte[] ipv4 = EmbeddedIpv4.of(literal, false);
        return ipv4 != null ? ipv4 : literal;
    }

    private static final byte[] IPV4_LOOPBACK = {127, 0, 0, 1};
    private static final byte[] IPV6_LOOPBACK = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

    /**
     * Whether a connection to the address reaches this host: loopback in either family, or the wildcard address
     * ({@code 0.0.0.0}, {@code ::}), which a connection treats as this host.
     */
    private static boolean isThisHost(byte[] canonicalAddress) {
        if (canonicalAddress.length == 4) {
            return canonicalAddress[0] == 127 || (canonicalAddress[0] == 0 && canonicalAddress[1] == 0 && canonicalAddress[2] == 0 && canonicalAddress[3] == 0);
        }
        for (int i = 0; i < 15; i++) {
            if (canonicalAddress[i] != 0) {
                return false;
            }
        }
        return (canonicalAddress[15] & 0xFE) == 0;
    }

    private static final class HostList {
        private final Set<String> names;
        private final String[] domains;
        private final Cidr[] cidrs;
        private final boolean listsThisHost;
        private final boolean hasIpv6Blocks;

        private HostList(Set<String> names, List<String> domains, List<Cidr> cidrs) {
            this.names = names;
            this.domains = domains.toArray(new String[0]);
            this.cidrs = cidrs.toArray(new Cidr[0]);
            this.listsThisHost = matchesAddress(IPV4_LOOPBACK) || matchesAddress(IPV6_LOOPBACK);
            this.hasIpv6Blocks = cidrs.stream().anyMatch(cidr -> cidr.network.length == 16);
        }

        @Nullable
        static HostList parse(String propertyName, String hosts) {
            Set<String> names = new HashSet<>();
            List<String> domains = new ArrayList<>();
            List<Cidr> cidrs = new ArrayList<>();
            for (String rawEntry : hosts.split(",")) {
                String entry = rawEntry.trim().toLowerCase(Locale.ROOT);
                if (entry.isEmpty()) {
                    continue;
                }
                int slash = entry.indexOf('/');
                if (slash >= 0) {
                    cidrs.add(Cidr.parse(propertyName, entry, slash));
                    continue;
                }
                String unbracketed = stripBrackets(entry);
                byte[] literal = NetUtil.createByteArrayFromIpAddressString(unbracketed);
                if (literal != null) {
                    byte[] address = canonicalEntry(literal);
                    cidrs.add(new Cidr(address, address.length * 8));
                    continue;
                }
                if (entry.endsWith(".")) {
                    entry = entry.substring(0, entry.length() - 1);
                }
                if (entry.startsWith("*.") || entry.startsWith(".")) {
                    String domain = entry.substring(entry.indexOf('.') + 1);
                    requireHostName(propertyName, rawEntry, domain);
                    names.add(domain);
                    domains.add("." + domain);
                    continue;
                }
                requireHostName(propertyName, rawEntry, entry);
                if (entry.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'))) {
                    // 127.1 or 2130706433: an address the JDK resolves but Netty does not parse, so never a name
                    throw invalidEntry(propertyName, rawEntry);
                }
                names.add(entry);
            }
            return names.isEmpty() && cidrs.isEmpty() ? null : new HostList(names, domains, cidrs);
        }

        private static void requireHostName(String propertyName, String rawEntry, String name) {
            if (name.isEmpty()) {
                throw invalidEntry(propertyName, rawEntry);
            }
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_')) {
                    throw invalidEntry(propertyName, rawEntry);
                }
            }
        }

        boolean hasAddresses() {
            return cidrs.length > 0;
        }

        boolean matchesName(String name) {
            if (names.contains(name)) {
                return true;
            }
            for (String domain : domains) {
                if (name.endsWith(domain)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Matches the address as given and as the IPv4 address it carries ({@link #canonical}), so neither spelling
         * gets past a block written in the other.
         */
        boolean matchesAddress(@Nullable byte[] address) {
            if (address == null || cidrs.length == 0) {
                return false;
            }
            byte[] canonicalAddress = canonical(address);
            for (Cidr cidr : cidrs) {
                if (cidr.matches(canonicalAddress) || (canonicalAddress != address && cidr.matches(address))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * As {@link #matchesAddress}, also matching an IPv4 address against IPv6 blocks in its IPv4-mapped and
         * IPv4-compatible spellings, so a deny list written in IPv6 cannot be got round in IPv4. Not used for an allow
         * list, where it would widen {@code ::/0} to every IPv4 address.
         */
        boolean matchesAnySpelling(@Nullable byte[] address) {
            if (matchesAddress(address)) {
                return true;
            }
            byte[] canonicalAddress = address != null ? canonical(address) : null;
            if (canonicalAddress == null || canonicalAddress.length != 4 || !hasIpv6Blocks) {
                return false;
            }
            byte[] compatible = new byte[16];
            System.arraycopy(canonicalAddress, 0, compatible, 12, 4);
            byte[] mapped = compatible.clone();
            mapped[10] = (byte) 0xFF;
            mapped[11] = (byte) 0xFF;
            for (Cidr cidr : cidrs) {
                if (cidr.matches(compatible) || cidr.matches(mapped)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * A list that holds a loopback address holds every address that reaches this host: the other family's
         * loopback and the wildcard address too.
         */
        boolean coversThisHost(@Nullable byte[] address) {
            return listsThisHost && address != null && isThisHost(canonical(address));
        }

        boolean denies(@Nullable byte[] address) {
            return matchesAnySpelling(address) || coversThisHost(address);
        }

        /**
         * Denies an address that may lead to a denied IPv4 address ({@link EmbeddedIpv4#possible}); such readings are
         * never matched against an allow list.
         */
        boolean deniesAnyPossibleIpv4(@Nullable byte[] address) {
            for (byte[] ipv4 : EmbeddedIpv4.possible(address)) {
                if (denies(ipv4)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Cidr {
        private final byte[] network;
        private final int prefixLength;

        private Cidr(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        static Cidr parse(String propertyName, String entry, int slash) {
            byte[] literal = NetUtil.createByteArrayFromIpAddressString(stripBrackets(entry.substring(0, slash)));
            if (literal == null) {
                throw invalidEntry(propertyName, entry);
            }
            int prefixLength;
            try {
                prefixLength = Integer.parseInt(entry.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw invalidEntry(propertyName, entry);
            }
            if (prefixLength < 0 || prefixLength > literal.length * 8) {
                throw invalidEntry(propertyName, entry);
            }
            byte[] network = literal;
            if (literal.length == 16 && canonicalEntry(literal).length == 4) {
                // ::ffff:a.b.c.d/n is the IPv4 block a.b.c.d/(n - 96)
                if (prefixLength < 96) {
                    throw invalidEntry(propertyName, entry);
                }
                network = canonicalEntry(literal);
                prefixLength -= 96;
            }
            return new Cidr(network, prefixLength);
        }

        boolean matches(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int wholeBytes = prefixLength / 8;
            for (int i = 0; i < wholeBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }

    private static IllegalArgumentException invalidEntry(String propertyName, String entry) {
        return new IllegalArgumentException(propertyName + " entry \"" + entry.trim() + "\" is not a host name, *.domain, IP address or CIDR block");
    }
}

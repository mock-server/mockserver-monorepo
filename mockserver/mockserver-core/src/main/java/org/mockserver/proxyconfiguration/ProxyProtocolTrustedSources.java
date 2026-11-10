package org.mockserver.proxyconfiguration;

import io.netty.util.NetUtil;
import org.mockserver.matchers.ClientIpMatcher;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.mockserver.model.NottableString.string;

/**
 * The connection sources ({@code proxyProtocolTrustedSources}) a PROXY protocol header is accepted from: IP addresses
 * and CIDR blocks, IPv4 or IPv6, comma-separated. A source is the TCP peer of the connection, never an address the
 * header itself names. An IPv4-mapped IPv6 peer ({@code ::ffff:a.b.c.d}) is matched as the IPv4 address it carries.
 * <p>
 * Entries are stricter than an expectation's {@code clientIp}, because a mistake here widens trust silently: a block
 * with bits set after its prefix, a block covering every address or every IPv4 address, a zone ID, a leading-zero
 * IPv4 part, the IPv4-compatible spelling {@code ::a.b.c.d}, negation and host names are all refused. Each entry is
 * checked on its own, so entries that together cover every address ({@code 0.0.0.0/1, 128.0.0.0/1}) are accepted.
 */
public final class ProxyProtocolTrustedSources {

    public static final String PROPERTY_NAME = "proxyProtocolTrustedSources";

    private static final byte[] IPV4_MAPPED_PREFIX = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF};

    private final ClientIpMatcher[] ranges;
    private final String description;

    private ProxyProtocolTrustedSources(List<ClientIpMatcher> ranges, List<String> entries) {
        this.ranges = ranges.toArray(new ClientIpMatcher[0]);
        this.description = String.join(", ", entries);
    }

    /**
     * @return the sources listed, or {@code null} when the value is blank, so a PROXY header is accepted from any source
     * @throws IllegalArgumentException naming the entry when one is not a single IP address or CIDR block, or when a
     *                                  value that is not blank lists no entry
     */
    @Nullable
    public static ProxyProtocolTrustedSources parse(@Nullable String sources) {
        if (sources == null || sources.trim().isEmpty()) {
            return null;
        }
        List<ClientIpMatcher> ranges = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        for (String rawEntry : sources.split(",")) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            ranges.add(range(entry));
            entries.add(entry);
        }
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException(PROPERTY_NAME + " \"" + sources + "\" lists no IP address or CIDR block; leave it empty to accept a PROXY protocol header from any source");
        }
        return new ProxyProtocolTrustedSources(ranges, entries);
    }

    /**
     * @throws IllegalArgumentException naming the entry when the list cannot be parsed
     */
    public static void validate(@Nullable String sources) {
        parse(sources);
    }

    private static ClientIpMatcher range(String entry) {
        if (entry.indexOf('%') >= 0) {
            throw refused(entry, "has a zone ID");
        }
        int slash = entry.indexOf('/');
        String address = slash < 0 ? entry : entry.substring(0, slash);
        if (address.length() > 2 && address.charAt(0) == '[' && address.charAt(address.length() - 1) == ']') {
            address = address.substring(1, address.length() - 1);
        }
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(address);
        if (bytes == null) {
            throw notAnAddress(entry);
        }
        if (hasLeadingZeroIpv4Part(address)) {
            throw refused(entry, "has an IPv4 part with a leading zero, which some readers take as octal");
        }
        if (isIpv4CompatibleSpelling(address)) {
            throw refused(entry, "is an IPv4-compatible IPv6 address (::a.b.c.d), a deprecated spelling; write the IPv4 address");
        }
        int prefix = slash < 0 ? bytes.length * 8 : prefixLength(entry, entry.substring(slash + 1), bytes.length * 8);
        if (prefix == 0) {
            throw refused(entry, "covers every address");
        }
        if (bytes.length == 16 && startsWith(bytes, IPV4_MAPPED_PREFIX, 96)) {
            if (prefix <= 96) {
                throw refused(entry, "covers every IPv4 address");
            }
            bytes = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
            prefix -= 96;
        } else if (bytes.length == 16 && prefix < 96 && startsWith(IPV4_MAPPED_PREFIX, bytes, prefix)) {
            // an IPv4 peer is matched as ::ffff:a.b.c.d, which this block contains
            throw refused(entry, "covers every IPv4 address");
        }
        for (int bit = prefix; bit < bytes.length * 8; bit++) {
            if ((bytes[bit >>> 3] & (0x80 >>> (bit & 7))) != 0) {
                throw refused(entry, "has bits set after its prefix length, so it is wider than it looks");
            }
        }
        try {
            return ClientIpMatcher.compile(null, string(entry));
        } catch (IllegalArgumentException e) {
            throw notAnAddress(entry);
        }
    }

    private static int prefixLength(String entry, String text, int maximum) {
        if (text.isEmpty() || text.length() > 3 || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw notAnAddress(entry);
        }
        int prefix = Integer.parseInt(text);
        if (prefix > maximum) {
            throw notAnAddress(entry);
        }
        return prefix;
    }

    private static boolean startsWith(byte[] address, byte[] prefix, int bits) {
        for (int bit = 0; bit < bits; bit++) {
            int mask = 0x80 >>> (bit & 7);
            if ((address[bit >>> 3] & mask) != (prefix[bit >>> 3] & mask)) {
                return false;
            }
        }
        return true;
    }

    // Netty reads ::a.b.c.d as the IPv4-mapped ::ffff:a.b.c.d, so it would be trusted as that IPv4 address
    private static boolean isIpv4CompatibleSpelling(String address) {
        int lastColon = address.lastIndexOf(':');
        return lastColon >= 0 && address.indexOf('.', lastColon) > lastColon && address.substring(0, lastColon).chars().allMatch(c -> c == ':' || c == '0');
    }

    private static boolean hasLeadingZeroIpv4Part(String address) {
        if (address.indexOf('.') < 0) {
            return false;
        }
        for (String part : address.substring(address.lastIndexOf(':') + 1).split("\\.")) {
            if (part.length() > 1 && part.charAt(0) == '0') {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a connection from this peer may send a PROXY protocol header; false for a peer that is not an IP socket.
     */
    public boolean trusts(@Nullable SocketAddress peer) {
        if (!(peer instanceof InetSocketAddress)) {
            return false;
        }
        InetAddress address = ((InetSocketAddress) peer).getAddress();
        if (address == null) {
            return false;
        }
        byte[] bytes = ClientIpMatcher.normalise(address.getAddress());
        for (ClientIpMatcher range : ranges) {
            if (range.matchesAddress(bytes)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The entries in force, as listed.
     */
    @Override
    public String toString() {
        return description;
    }

    private static IllegalArgumentException notAnAddress(String entry) {
        return new IllegalArgumentException(PROPERTY_NAME + " entry \"" + entry + "\" is not an IP address or CIDR block");
    }

    private static IllegalArgumentException refused(String entry, String reason) {
        return new IllegalArgumentException(PROPERTY_NAME + " entry \"" + entry + "\" " + reason);
    }
}

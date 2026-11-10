package org.mockserver.matchers;

import io.netty.util.NetUtil;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NottableOptionalString;
import org.mockserver.model.NottableSchemaString;
import org.mockserver.model.NottableString;

/**
 * Matches the client (TCP peer) address of a request against an IP address or a CIDR range, IPv4 or IPv6,
 * optionally negated. The range is parsed once, when the expectation is created, into its address bytes and
 * prefix length; matching a request only compares bytes, so it never parses text or resolves a host name.
 * <p>
 * The address is the socket's peer (or the source a PROXY protocol header named), never a value from
 * {@code X-Forwarded-For} or any other HTTP header. An IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) is treated
 * as the IPv4 address it carries.
 */
public final class ClientIpMatcher implements Matcher<HttpRequest> {

    private static final byte[] NO_ADDRESS = new byte[0];
    // set the first time an expectation or filter with a clientIp is applied, and never cleared; until then the
    // transport records only the remote address text, so connections pay nothing for a feature nobody uses
    private static volatile boolean inUse;
    private final MockServerLogger mockServerLogger;
    private final byte[] network;
    private final int prefixLength;
    private final boolean not;
    private final String description;

    private ClientIpMatcher(MockServerLogger mockServerLogger, byte[] network, int prefixLength, boolean not, String description) {
        this.mockServerLogger = mockServerLogger;
        this.network = network;
        this.prefixLength = prefixLength;
        this.not = not;
        this.description = description;
    }

    /**
     * Compiles a client IP criterion such as {@code "192.168.1.10"}, {@code "10.0.0.0/8"} or {@code "!2001:db8::/32"}.
     *
     * @throws IllegalArgumentException when the value is not an IP address or CIDR range
     */
    public static ClientIpMatcher compile(MockServerLogger mockServerLogger, NottableString clientIp) {
        if (clientIp == null) {
            throw new IllegalArgumentException("clientIp must be an IP address or CIDR range");
        }
        if (clientIp instanceof NottableSchemaString || clientIp instanceof NottableOptionalString || clientIp.getParameterStyle() != null) {
            throw new IllegalArgumentException("clientIp must be an IP address or CIDR range, optionally negated, but was \"" + clientIp + "\"");
        }
        String value = clientIp.getValue() == null ? "" : clientIp.getValue().trim();
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        if (address.length() > 2 && address.charAt(0) == '[' && address.charAt(address.length() - 1) == ']') {
            address = address.substring(1, address.length() - 1);
        }
        byte[] bytes = address.isEmpty() ? null : NetUtil.createByteArrayFromIpAddressString(address);
        if (bytes == null) {
            throw new IllegalArgumentException("clientIp \"" + value + "\" is not an IP address or CIDR range; host names are not supported");
        }
        int prefix = bytes.length * 8;
        if (slash >= 0) {
            prefix = parsePrefix(value, value.substring(slash + 1), prefix);
        }
        if (isIpv4Mapped(bytes)) {
            if (prefix >= 96) {
                bytes = ipv4Of(bytes);
                prefix -= 96;
            }
        }
        for (int bit = prefix; bit < bytes.length * 8; bit++) {
            bytes[bit >>> 3] &= (byte) ~(0x80 >>> (bit & 7));
        }
        return new ClientIpMatcher(mockServerLogger, bytes, prefix, clientIp.isNot(), (clientIp.isNot() ? "!" : "") + value);
    }

    /**
     * Whether any expectation or filter has applied a client IP matcher in this JVM, so the transport should keep
     * the peer's address for it.
     */
    public static boolean isInUse() {
        return inUse;
    }

    /**
     * As {@link #compile}, for a matcher that will compare requests: from now on the transport keeps each
     * connection's peer address so the comparison never parses text.
     */
    static ClientIpMatcher compileForMatching(MockServerLogger mockServerLogger, NottableString clientIp) {
        ClientIpMatcher matcher = compile(mockServerLogger, clientIp);
        inUse = true;
        return matcher;
    }

    private static int parsePrefix(String value, String prefixText, int maximum) {
        if (prefixText.isEmpty() || prefixText.length() > 3) {
            throw new IllegalArgumentException("clientIp \"" + value + "\" has an invalid prefix length, it must be between 0 and " + maximum);
        }
        int prefix = 0;
        for (int i = 0; i < prefixText.length(); i++) {
            char digit = prefixText.charAt(i);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("clientIp \"" + value + "\" has an invalid prefix length, it must be between 0 and " + maximum);
            }
            prefix = prefix * 10 + (digit - '0');
        }
        if (prefix > maximum) {
            throw new IllegalArgumentException("clientIp \"" + value + "\" has an invalid prefix length, it must be between 0 and " + maximum);
        }
        return prefix;
    }

    @Override
    public boolean matches(MatchDifference context, HttpRequest request) {
        byte[] actual = request == null ? null : addressOf(request);
        boolean result = actual != null && inRange(actual) != not;
        if (!result && context != null) {
            context.addDifference(mockServerLogger, "client ip match failed expected:{} found:{}", description, request != null ? request.getRemoteAddress() : null);
        }
        return result;
    }

    /**
     * Whether an address, in the form {@link #normalise} gives, is in the range, or outside it when negated.
     */
    public boolean matchesAddress(byte[] address) {
        return address != null && address.length > 0 && inRange(address) != not;
    }

    private boolean inRange(byte[] actual) {
        if (actual.length != network.length && !(actual.length == 4 && network.length == 16)) {
            return false;
        }
        int fullBytes = prefixLength >>> 3;
        for (int i = 0; i < fullBytes; i++) {
            if (byteAt(actual, i) != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength & 7;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (byteAt(actual, fullBytes) & mask) == (network[fullBytes] & mask);
    }

    // reads an IPv4 address as the IPv4-mapped IPv6 address ::ffff:a.b.c.d when the range is IPv6
    private byte byteAt(byte[] actual, int index) {
        if (actual.length == network.length) {
            return actual[index];
        }
        return index < 10 ? 0 : index < 12 ? (byte) 0xFF : actual[index - 12];
    }

    /**
     * The request's client address bytes, or null when it has none. A request the Netty server mapped after a
     * clientIp matcher was first applied carries its connection's holder, whose bytes are derived from the peer's
     * InetAddress on first use; any other request (mapped earlier, deserialised, or from the servlet) has them
     * parsed once from its {@code remoteAddress} text and remembered on the request.
     */
    private static byte[] addressOf(HttpRequest request) {
        byte[] bytes = request.getRemoteAddressBytes();
        if (bytes == null) {
            bytes = parseRemoteAddress(request.getRemoteAddress());
            request.withRemoteAddressBytes(bytes);
        }
        return bytes.length == 0 ? null : bytes;
    }

    /**
     * The address bytes of a {@code remoteAddress} such as {@code 127.0.0.1:52000}, {@code [::1]:52000} or
     * {@code host/10.0.0.1:52000}; an empty array when it holds no IP literal. Never resolves a host name.
     */
    static byte[] parseRemoteAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isEmpty()) {
            return NO_ADDRESS;
        }
        String host = remoteAddress.substring(remoteAddress.lastIndexOf('/') + 1);
        if (!host.isEmpty() && host.charAt(0) == '[') {
            int end = host.indexOf(']');
            host = end < 0 ? host.substring(1) : host.substring(1, end);
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0 && host.indexOf(':', colon + 1) < 0) {
                host = host.substring(0, colon);
            }
        }
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(host);
        int lastColon = host.lastIndexOf(':');
        if (bytes == null && lastColon > 0) {
            // an unbracketed IPv6 address followed by a port, as a servlet container's "host:port" produces
            bytes = NetUtil.createByteArrayFromIpAddressString(host.substring(0, lastColon));
        }
        return bytes == null ? NO_ADDRESS : normalise(bytes);
    }

    /**
     * The address in the form matching compares: IPv4-mapped IPv6 addresses become their IPv4 address.
     */
    public static byte[] normalise(byte[] address) {
        return isIpv4Mapped(address) ? ipv4Of(address) : address;
    }

    private static boolean isIpv4Mapped(byte[] address) {
        if (address.length != 16 || address[10] != (byte) 0xFF || address[11] != (byte) 0xFF) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ipv4Of(byte[] mapped) {
        return new byte[]{mapped[12], mapped[13], mapped[14], mapped[15]};
    }

    @Override
    public boolean isBlank() {
        return false;
    }

    @Override
    public String toString() {
        return description;
    }
}

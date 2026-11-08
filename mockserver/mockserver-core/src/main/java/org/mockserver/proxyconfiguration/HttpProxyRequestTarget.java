package org.mockserver.proxyconfiguration;

import io.netty.util.NetUtil;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.SocketAddress;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * The destination a forwarded request names, read strictly so that the host the forward-target checks see is the host
 * an upstream proxy reads, whether in a request line, a CONNECT or SOCKS request, or a binary relay's tunnel.
 * <p>
 * A host is accepted only as ASCII letters, digits, '.', '-' and '_' with no empty label, or as an IPv6 literal
 * without a zone (whose dotted IPv4 tail, if any, follows the IPv4 rule below). A host whose last label is all digits
 * or starts with {@code 0x} is an IPv4 address, accepted only as four decimal octets without leading zeros, as
 * resolvers read other numeric forms (octal, hex, fewer parts) differently.
 */
public final class HttpProxyRequestTarget {

    private HttpProxyRequestTarget() {
    }

    /**
     * @param authority the request line's authority, {@code host} or {@code host:port}, an IPv6 literal in brackets
     * @param host      the host in it, an IPv6 literal without brackets, as the forward-target checks are given it
     */
    public record Authority(String authority, String host) {
    }

    /**
     * @param request a plain HTTP request to be sent through {@code forwardHttpProxy}
     * @return its authority, or null when it has neither a Host header nor a remote address
     * @throws MalformedForwardTargetException when it has more than one Host header, or its Host header (or, with
     *                                         none, its remote address) is not exactly one {@code host} or
     *                                         {@code host:port}
     */
    @Nullable
    public static Authority of(HttpRequest request) {
        List<String> hostHeaders = request.getHeader("Host");
        if (hostHeaders.size() > 1) {
            throw new MalformedForwardTargetException("Forward target refused: the request has " + hostHeaders.size() + " Host headers, so the destination to send forwardHttpProxy is not one host");
        }
        boolean fromHostHeader = hostHeaders.size() == 1 && isNotBlank(hostHeaders.get(0));
        String source;
        if (fromHostHeader) {
            source = hostHeaders.get(0);
        } else if (isNotBlank(request.getRemoteAddress())) {
            source = request.getRemoteAddress();
        } else {
            return null;
        }
        Authority authority = parse(source);
        if (authority == null) {
            throw notOneHost(fromHostHeader ? "Host" : "remote address");
        }
        return authority;
    }

    /**
     * The host a request is sent to: {@code remoteAddress}'s host when given, otherwise the host of the request's
     * socket address or else of its first Host header, which a tunnel through an upstream proxy is opened to.
     *
     * @return that host, or null when the request names none
     * @throws MalformedForwardTargetException when that host, or the Host header it is read from, is not exactly one
     *                                         host or {@code host:port}
     */
    @Nullable
    public static String destinationHost(HttpRequest request, @Nullable InetSocketAddress remoteAddress) {
        if (remoteAddress != null) {
            return requireHost(remoteAddress.getHostString(), "remote address");
        }
        SocketAddress socketAddress = request.getSocketAddress();
        if (socketAddress != null && socketAddress.getHost() != null) {
            return requireHost(socketAddress.getHost(), "socket address");
        }
        String hostHeader = request.getFirstHeader("Host");
        if (isBlank(hostHeader)) {
            return null;
        }
        Authority authority = parse(hostHeader);
        if (authority == null) {
            throw notOneHost("Host");
        }
        return authority.host();
    }

    /**
     * @return {@code destination}, a binary connection's tunnel destination, which the upstream proxy is sent by name
     * @throws MalformedForwardTargetException when its host is not exactly one host (see the class description)
     */
    public static InetSocketAddress requireTunnelDestination(InetSocketAddress destination) {
        String host = destination.getHostString();
        if (host == null || !isHost(host)) {
            throw new MalformedForwardTargetException("Forward target refused: the connection's destination is not exactly one host, so no tunnel is opened to it through the upstream proxy (a host is letters, digits, '.', '-' and '_' with no empty label, an IPv4 address as four decimal octets without leading zeros, or an IPv6 address without a zone)");
        }
        return destination;
    }

    /**
     * @return {@code value} as an authority, or null unless it is exactly a host (see the class description) or a
     * bracketed IPv6 literal without a zone, optionally followed by ':' and a port from 1 to 65535 without leading
     * zeros: no user info, path, query, fragment, whitespace or control characters
     */
    @Nullable
    static Authority parse(String value) {
        int length = value.length();
        int hostEnd;
        String host = null;
        if (length > 0 && value.charAt(0) == '[') {
            hostEnd = value.indexOf(']') + 1;
            if (hostEnd < 3) {
                return null;
            }
            host = value.substring(1, hostEnd - 1);
            if (!isIpv6Literal(host)) {
                return null;
            }
        } else {
            hostEnd = 0;
            while (hostEnd < length && isHostCharacter(value.charAt(hostEnd))) {
                hostEnd++;
            }
            if (hostEnd == 0) {
                return null;
            }
        }
        if (hostEnd < length && (value.charAt(hostEnd) != ':' || !isPort(value, hostEnd + 1))) {
            return null;
        }
        if (host == null) {
            host = hostEnd == length ? value : value.substring(0, hostEnd);
            if (hasEmptyLabel(host) || isAmbiguousIpv4(host)) {
                return null;
            }
        }
        return new Authority(value, host);
    }

    /**
     * @return whether {@code host} is a host (see the class description), an IPv6 literal with or without brackets
     */
    static boolean isHost(String host) {
        int length = host.length();
        if (length == 0) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return length > 2 && host.charAt(0) == '[' && host.charAt(length - 1) == ']'
                ? isIpv6Literal(host.substring(1, length - 1))
                : isIpv6Literal(host);
        }
        for (int i = 0; i < length; i++) {
            if (!isHostCharacter(host.charAt(i))) {
                return false;
            }
        }
        return !hasEmptyLabel(host) && !isAmbiguousIpv4(host);
    }

    /**
     * One trailing '.' is the root, not an empty label.
     */
    private static boolean hasEmptyLabel(String host) {
        return host.charAt(0) == '.' || host.contains("..");
    }

    /**
     * @return whether {@code host} is read as an IPv4 address (its last label, ignoring one trailing '.', is all
     * digits or starts with {@code 0x}) but is not four decimal octets from 0 to 255 without leading zeros
     */
    static boolean isAmbiguousIpv4(String host) {
        if (host.indexOf(':') >= 0) {
            return false;
        }
        int end = host.length();
        if (end > 0 && host.charAt(end - 1) == '.') {
            end--;
        }
        int lastLabel = host.lastIndexOf('.', end - 1) + 1;
        if (lastLabel >= end) {
            return false;
        }
        boolean numeric = end - lastLabel >= 2 && host.charAt(lastLabel) == '0' && (host.charAt(lastLabel + 1) == 'x' || host.charAt(lastLabel + 1) == 'X');
        if (!numeric) {
            numeric = true;
            for (int i = lastLabel; i < end && numeric; i++) {
                numeric = host.charAt(i) >= '0' && host.charAt(i) <= '9';
            }
        }
        return numeric && !isCanonicalIpv4(host);
    }

    private static boolean isCanonicalIpv4(String host) {
        int octets = 0;
        int start = 0;
        int length = host.length();
        while (start <= length) {
            int end = host.indexOf('.', start);
            if (end < 0) {
                end = length;
            }
            int digits = end - start;
            if (digits < 1 || digits > 3 || (digits > 1 && host.charAt(start) == '0')) {
                return false;
            }
            int octet = 0;
            for (int i = start; i < end; i++) {
                char character = host.charAt(i);
                if (character < '0' || character > '9') {
                    return false;
                }
                octet = octet * 10 + (character - '0');
            }
            if (octet > 255 || ++octets > 4) {
                return false;
            }
            start = end + 1;
        }
        return octets == 4;
    }

    /**
     * @return whether {@code host} is an IPv6 address without a zone, whose dotted IPv4 tail, if it has one, is four
     * decimal octets without leading zeros
     */
    private static boolean isIpv6Literal(String host) {
        if (host.indexOf('%') >= 0 || !NetUtil.isValidIpV6Address(host)) {
            return false;
        }
        return host.indexOf('.') < 0 || isCanonicalIpv4(host.substring(host.lastIndexOf(':') + 1));
    }

    private static String requireHost(String host, String source) {
        if (host == null || !isHost(host)) {
            throw notOneHost(source);
        }
        return host;
    }

    private static MalformedForwardTargetException notOneHost(String source) {
        return new MalformedForwardTargetException("Forward target refused: the request's " + source + " is not exactly one host or host:port, so it is not sent (a host is letters, digits, '.', '-' and '_' with no empty label, an IPv4 address as four decimal octets without leading zeros, or an IPv6 address in brackets)");
    }

    private static boolean isHostCharacter(char character) {
        return (character >= 'a' && character <= 'z')
            || (character >= 'A' && character <= 'Z')
            || (character >= '0' && character <= '9')
            || character == '.' || character == '-' || character == '_';
    }

    private static boolean isPort(String value, int start) {
        int digits = value.length() - start;
        if (digits < 1 || digits > 5 || (digits > 1 && value.charAt(start) == '0')) {
            return false;
        }
        int port = 0;
        for (int i = start; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') {
                return false;
            }
            port = port * 10 + (character - '0');
        }
        return port > 0 && port <= 65535;
    }
}

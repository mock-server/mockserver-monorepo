package org.mockserver.socket;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class SocketAddresses {

    /**
     * The address of the client a connection carries traffic for, when that is not the connection's peer: the source
     * an accepted PROXY protocol header carried (with {@code transparentProxyEnabled}, from any source unless
     * {@code proxyProtocolTrustedSources} is set), or, on MockServer's end of a CONNECT or SOCKS tunnel's relay
     * loopback, the tunnel client's address.
     */
    public static final AttributeKey<InetSocketAddress> PROXY_PROTOCOL_SOURCE = AttributeKey.valueOf("PROXY_PROTOCOL_SOURCE");

    private SocketAddresses() {
    }

    /**
     * An address for a host without looking its name up: an IP literal is parsed into a resolved address, and a
     * host name is left unresolved, to be resolved where the connection is made (by Netty's resolver, or by an
     * upstream proxy the connection is tunnelled through).
     */
    public static InetSocketAddress unresolvedUnlessIpLiteral(String host, int port) {
        return NetUtil.isValidIpV4Address(host) || NetUtil.isValidIpV6Address(host)
            ? new InetSocketAddress(host, port)
            : InetSocketAddress.createUnresolved(host, port);
    }

    /**
     * The address to connect to now: a resolved address as it is, and an unresolved one looked up, which blocks. A
     * name that does not resolve is returned unresolved, so connecting to it fails with an
     * {@link java.nio.channels.UnresolvedAddressException}.
     */
    public static InetSocketAddress resolvedNow(InetSocketAddress address) {
        return address.isUnresolved() ? new InetSocketAddress(address.getHostString(), address.getPort()) : address;
    }

    /**
     * The address of the client a connection carries traffic for: the source a PROXY protocol header named, or the
     * client of the tunnel whose relay loopback this is, where either applies, or else the connection's own peer.
     * It is for recording and matching requests, not an identity to authorise by: a PROXY header can supply it.
     */
    public static SocketAddress clientAddress(Channel channel) {
        if (channel.hasAttr(PROXY_PROTOCOL_SOURCE)) {
            InetSocketAddress source = channel.attr(PROXY_PROTOCOL_SOURCE).get();
            if (source != null) {
                return source;
            }
        }
        return channel.remoteAddress();
    }
}

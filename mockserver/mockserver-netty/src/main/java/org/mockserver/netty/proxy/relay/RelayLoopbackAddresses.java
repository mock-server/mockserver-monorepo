package org.mockserver.netty.proxy.relay;

import io.netty.channel.Channel;
import org.mockserver.netty.connection.TunnelProxyClient;
import org.mockserver.socket.SocketAddresses;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The address pairs of the loopback connections {@link RelayConnectHandler} opens to MockServer, each with the proxy
 * client's channel of its tunnel and that client's address, so the accepting side can tell its own relay's loopback
 * leg from a client that merely sends the same {@code PROXIED_} preamble, and can reach the tunnel's other leg. Each
 * pair is held only while its connection is open. Both ends are matched, not just the source: the same source
 * address can be in use by a connection to a different destination.
 */
public final class RelayLoopbackAddresses {

    /**
     * @param clientAddress the tunnel client's address, read from MockServer's own state of the proxy client's
     *                      connection when the loopback was opened (never from bytes sent through the tunnel), or
     *                      null when it has no IP address
     */
    private record Relay(Channel proxyClient, InetSocketAddress clientAddress) {
    }

    // key: (the relay end's local address, the relay end's remote address)
    private static final Map<List<SocketAddress>, Relay> RELAYS = new ConcurrentHashMap<>();

    private RelayLoopbackAddresses() {
    }

    /**
     * Must be called once the loopback is connected and before it sends its preamble.
     */
    static void register(Channel loopback, Channel proxyClient) {
        List<SocketAddress> key = key(loopback.localAddress(), loopback.remoteAddress());
        if (key == null) {
            return;
        }
        Relay relay = new Relay(proxyClient, SocketAddresses.clientAddress(proxyClient) instanceof InetSocketAddress client ? client : null);
        if (RELAYS.putIfAbsent(key, relay) == null) {
            loopback.closeFuture().addListener(future -> RELAYS.remove(key, relay));
        }
    }

    /**
     * @return whether an accepted connection is the loopback leg of one of this JVM's relays
     */
    public static boolean isRelayLoopback(Channel accepted) {
        return relayOf(accepted) != null;
    }

    /**
     * @return the proxy client's channel of the tunnel whose loopback leg an accepted connection is, or null if it is
     * not one of this JVM's relays' loopback legs
     */
    static Channel proxyClientOf(Channel accepted) {
        Relay relay = relayOf(accepted);
        return relay != null ? relay.proxyClient() : null;
    }

    private static Relay relayOf(Channel accepted) {
        List<SocketAddress> key = key(accepted.remoteAddress(), accepted.localAddress());
        return key != null ? RELAYS.get(key) : null;
    }

    private static List<SocketAddress> key(SocketAddress relayEnd, SocketAddress mockServerEnd) {
        return relayEnd != null && mockServerEnd != null ? List.of(relayEnd, mockServerEnd) : null;
    }

    /**
     * On MockServer's end of a relay loopback: keeps the tunnel's proxy client on the channel, so it is still known
     * after the relay's end of the loopback closes and its address is no longer held, and sets the tunnel client's
     * address as the connection's client address ({@link SocketAddresses#clientAddress}), so the requests it carries
     * are recorded as from that client rather than from the loopback.
     */
    public static void rememberProxyClient(Channel accepted) {
        Relay relay = relayOf(accepted);
        if (relay != null) {
            rememberProxyClient(accepted, relay.proxyClient());
            if (relay.clientAddress() != null) {
                accepted.attr(SocketAddresses.PROXY_PROTOCOL_SOURCE).set(relay.clientAddress());
            }
        }
    }

    static void rememberProxyClient(Channel accepted, Channel proxyClient) {
        TunnelProxyClient.remember(accepted, proxyClient);
    }
}

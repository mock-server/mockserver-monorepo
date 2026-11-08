package org.mockserver.netty.proxy.relay;

import io.netty.channel.Channel;
import org.mockserver.netty.connection.TunnelProxyClient;

import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The address pairs of the loopback connections {@link RelayConnectHandler} opens to MockServer, each with the proxy
 * client's channel of its tunnel, so the accepting side can tell its own relay's loopback leg from a client that merely
 * sends the same {@code PROXIED_} preamble, and can reach the tunnel's other leg. Each pair is held only while its
 * connection is open. Both ends are matched, not just the source: the same source address can be in use by a
 * connection to a different destination.
 */
public final class RelayLoopbackAddresses {

    // key: (the relay end's local address, the relay end's remote address)
    private static final Map<List<SocketAddress>, Channel> PROXY_CLIENTS = new ConcurrentHashMap<>();

    private RelayLoopbackAddresses() {
    }

    /**
     * Must be called once the loopback is connected and before it sends its preamble.
     */
    static void register(Channel loopback, Channel proxyClient) {
        List<SocketAddress> key = key(loopback.localAddress(), loopback.remoteAddress());
        if (key != null && PROXY_CLIENTS.putIfAbsent(key, proxyClient) == null) {
            loopback.closeFuture().addListener(future -> PROXY_CLIENTS.remove(key, proxyClient));
        }
    }

    /**
     * @return whether an accepted connection is the loopback leg of one of this JVM's relays
     */
    public static boolean isRelayLoopback(Channel accepted) {
        return proxyClientOf(accepted) != null;
    }

    /**
     * @return the proxy client's channel of the tunnel whose loopback leg an accepted connection is, or null if it is
     * not one of this JVM's relays' loopback legs
     */
    static Channel proxyClientOf(Channel accepted) {
        List<SocketAddress> key = key(accepted.remoteAddress(), accepted.localAddress());
        return key != null ? PROXY_CLIENTS.get(key) : null;
    }

    private static List<SocketAddress> key(SocketAddress relayEnd, SocketAddress mockServerEnd) {
        return relayEnd != null && mockServerEnd != null ? List.of(relayEnd, mockServerEnd) : null;
    }

    /**
     * On MockServer's end of a relay loopback: keeps the tunnel's proxy client on the channel, so it is still known
     * after the relay's end of the loopback closes and its address is no longer held.
     */
    public static void rememberProxyClient(Channel accepted) {
        Channel proxyClient = proxyClientOf(accepted);
        if (proxyClient != null) {
            rememberProxyClient(accepted, proxyClient);
        }
    }

    static void rememberProxyClient(Channel accepted, Channel proxyClient) {
        TunnelProxyClient.remember(accepted, proxyClient);
    }
}

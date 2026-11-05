package org.mockserver.netty.proxy.relay;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The local addresses of the loopback connections {@link RelayConnectHandler} opens to MockServer, each with the proxy
 * client's channel of its tunnel, so the accepting side can tell its own relay's loopback leg from a client that merely
 * sends the same {@code PROXIED_} preamble, and can reach the tunnel's other leg. Each address is held only while its
 * connection is open.
 */
public final class RelayLoopbackAddresses {

    private static final Map<SocketAddress, Channel> PROXY_CLIENTS = new ConcurrentHashMap<>();
    private static final AttributeKey<Channel> PROXY_CLIENT = AttributeKey.valueOf("mockserver.relayProxyClient");

    private RelayLoopbackAddresses() {
    }

    /**
     * Must be called once the loopback is connected and before it sends its preamble.
     */
    static void register(Channel loopback, Channel proxyClient) {
        SocketAddress localAddress = loopback.localAddress();
        if (localAddress != null && PROXY_CLIENTS.putIfAbsent(localAddress, proxyClient) == null) {
            loopback.closeFuture().addListener(future -> PROXY_CLIENTS.remove(localAddress));
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
        SocketAddress remoteAddress = accepted.remoteAddress();
        return remoteAddress != null ? PROXY_CLIENTS.get(remoteAddress) : null;
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
        accepted.attr(PROXY_CLIENT).set(proxyClient);
    }

    /**
     * @return the proxy client remembered by {@link #rememberProxyClient}, or null
     */
    public static Channel rememberedProxyClient(Channel channel) {
        return channel.hasAttr(PROXY_CLIENT) ? channel.attr(PROXY_CLIENT).get() : null;
    }
}

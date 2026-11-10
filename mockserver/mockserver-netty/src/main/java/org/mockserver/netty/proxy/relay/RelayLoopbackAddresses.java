package org.mockserver.netty.proxy.relay;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.mockserver.authentication.ProxyAuthenticationContext;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.connection.TunnelProxyClient;

import java.net.SocketAddress;
import java.net.InetSocketAddress;
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
    private static final Map<List<SocketAddress>, Relay> PROXY_CLIENTS = new ConcurrentHashMap<>();
    private static final AttributeKey<SourceIdentity> SOURCE = AttributeKey.valueOf("MOCKSERVER_RELAY_ORIGINAL_SOURCE");

    /** Unknown source identity must remain unknown through nested relays. */
    public record SourceIdentity(InetSocketAddress address) { }

    private record Relay(Channel proxyClient, SourceIdentity source, ProxyAuthenticationContext authentication) { }

    private RelayLoopbackAddresses() {
    }

    /**
     * Must be called once the loopback is connected and before it sends its preamble.
     */
    static void register(Channel loopback, Channel proxyClient) {
        register(loopback, proxyClient, proxyClient.remoteAddress() instanceof InetSocketAddress source ? source : null);
    }

    static void register(Channel loopback, Channel proxyClient, InetSocketAddress source) {
        List<SocketAddress> key = key(loopback.localAddress(), loopback.remoteAddress());
        Relay relay = new Relay(proxyClient, new SourceIdentity(source),
            proxyClient.attr(HttpRequestHandler.PROXY_AUTHENTICATION_CONTEXT).get());
        if (key != null && PROXY_CLIENTS.putIfAbsent(key, relay) == null) {
            loopback.closeFuture().addListener(future -> PROXY_CLIENTS.remove(key, relay));
        }
    }

    /**
     * @return whether an accepted connection is the loopback leg of one of this JVM's relays
     */
    public static boolean isRelayLoopback(Channel accepted) {
        Relay relay = relayOf(accepted);
        if (relay != null) {
            rememberIdentity(accepted, relay);
        }
        return relay != null;
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
        return key != null ? PROXY_CLIENTS.get(key) : null;
    }

    private static void rememberIdentity(Channel accepted, Relay relay) {
        accepted.attr(SOURCE).setIfAbsent(relay.source());
        if (relay.authentication() != null) {
            accepted.attr(HttpRequestHandler.PROXY_AUTHENTICATION_CONTEXT).setIfAbsent(relay.authentication());
        }
    }

    public static SourceIdentity sourceIdentityOf(Channel accepted) {
        SourceIdentity source = accepted.attr(SOURCE).get();
        if (source == null) {
            Relay relay = relayOf(accepted);
            if (relay != null) {
                rememberIdentity(accepted, relay);
                source = accepted.attr(SOURCE).get();
            }
        }
        return source;
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

package org.mockserver.netty.proxy;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AttributeKey;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.socket.SocketAddresses;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Whether a proxy client sent a request through MockServer to another host, so its path, however much it reads like a
 * control-plane route ({@code /mockserver/clear}, {@code /status}), is matched and forwarded rather than served by
 * MockServer's own API.
 * <p>
 * Only two kinds of request are proxied: one inside a {@code CONNECT} or SOCKS tunnel, whose destination is the
 * tunnel's, and one whose request line names a full URL, whose destination is its {@code Host}. Any other request,
 * whatever its {@code Host}, is addressed to MockServer: a container, Service or ingress name for it is not known here.
 * A destination is another host only on positive evidence, so an ambiguous one keeps reaching the control plane. The
 * evidence is either:
 * <ul>
 * <li>a port none of MockServer's listeners is bound to, at a destination that is not loopback. A loopback port may be
 * relayed to MockServer ({@code kubectl port-forward}, {@code ssh -L}, a container's published port); or</li>
 * <li>a listening port at an IP address none of those listeners accepts. A name at a listening port may be one of
 * this host's, so it is MockServer.</li>
 * </ul>
 */
public final class ProxiedDestination {

    /**
     * On MockServer's end of a {@code CONNECT} or SOCKS tunnel's loopback, set when the tunnel's destination is another
     * host; connection-scoped, so HTTP/2 streams carried by the tunnel inherit it.
     */
    public static final AttributeKey<Boolean> TUNNELLED_TO_ANOTHER_HOST = AttributeKey.valueOf("mockserver.tunnelledToAnotherHost");

    private ProxiedDestination() {
    }

    /**
     * Records, on MockServer's end of a tunnel's loopback, whether the tunnel's destination is another host.
     */
    public static void rememberTunnelDestination(Channel loopback, LifeCycle server, InetSocketAddress destination) {
        if (isAnotherHost(server, destination)) {
            loopback.attr(TUNNELLED_TO_ANOTHER_HOST).set(Boolean.TRUE);
        }
    }

    /**
     * @return whether this channel (or the HTTP/2 stream's connection) is a tunnel to another host; never creates the
     * attribute
     */
    public static boolean tunnelledToAnotherHost(Channel channel) {
        return channel.hasAttr(TUNNELLED_TO_ANOTHER_HOST) && Boolean.TRUE.equals(channel.attr(TUNNELLED_TO_ANOTHER_HOST).get());
    }

    /**
     * For a handler that serves a control-plane route before {@code HttpState.handle} sees the request.
     *
     * @return whether the control plane must not serve this request: it is in a tunnel to another host, or a MockServer
     * forwarded it as a proxy, so serving it would grant the forward's identity to the proxy client
     */
    public static boolean notForTheControlPlane(Channel channel, HttpHeaders headers, HttpState httpState) {
        return tunnelledToAnotherHost(channel)
            || httpState != null && HttpState.isForwardedByAMockServer(headers.getAll(httpState.getUniqueLoopPreventionHeaderName()));
    }

    /**
     * @return whether this request names a full URL in its request line and has a {@code Host} that is another host
     */
    public static boolean absoluteFormToAnotherHost(LifeCycle server, HttpRequest request) {
        if (!request.isAbsoluteFormRequestTarget()) {
            return false;
        }
        String host = request.getFirstHeader("host");
        if (isBlank(host)) {
            return false;
        }
        InetSocketAddress destination;
        try {
            String[] hostParts = HttpRequest.splitHostPort(host.trim());
            int port = hostParts.length > 1 ? Integer.parseInt(hostParts[1]) : Boolean.TRUE.equals(request.isSecure()) ? 443 : 80;
            if (isBlank(hostParts[0]) || port < 1 || port > 65535) {
                return false;
            }
            destination = SocketAddresses.unresolvedUnlessIpLiteral(hostParts[0], port);
        } catch (RuntimeException unparsable) {
            return false;
        }
        return isAnotherHost(server, destination);
    }

    static boolean isAnotherHost(LifeCycle server, InetSocketAddress destination) {
        if (server == null || destination == null) {
            return false;
        }
        InetAddress address = destination.isUnresolved() ? null : destination.getAddress();
        if (isLoopback(destination, address)) {
            return false;
        }
        if (server.isListeningOnPort(destination.getPort())) {
            return address != null && !server.isListeningOn(destination);
        }
        return true;
    }

    private static boolean isLoopback(InetSocketAddress destination, InetAddress address) {
        if (address != null) {
            return address.isLoopbackAddress() || address.isAnyLocalAddress();
        }
        String host = destination.getHostString().toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.endsWith(".localhost");
    }
}

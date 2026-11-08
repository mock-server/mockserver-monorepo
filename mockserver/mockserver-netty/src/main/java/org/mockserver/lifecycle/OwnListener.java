package org.mockserver.lifecycle;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;

/**
 * Tells whether a destination is one of MockServer's own listeners, so a binary connection is never relayed back into
 * the server that accepted it: a tunnel inside a tunnel inside a tunnel, each holding connections open.
 */
public final class OwnListener {

    private static final AttributeKey<LifeCycle> SERVER = AttributeKey.valueOf("mockserver.ownListenerServer");

    private OwnListener() {
    }

    /**
     * Remembers the server that accepted this connection, whose listeners its binary relay may not connect to.
     */
    public static void remember(Channel accepted, LifeCycle server) {
        if (server != null) {
            accepted.attr(SERVER).set(server);
        }
    }

    /**
     * @return whether this destination is a listener of the server that accepted this connection; false for a
     * connection with no server remembered or a destination not yet resolved
     */
    public static boolean isOwnListener(Channel accepted, InetSocketAddress destination) {
        LifeCycle server = accepted.hasAttr(SERVER) ? accepted.attr(SERVER).get() : null;
        return server != null && server.isListeningOn(destination);
    }

    /**
     * @return whether a listener bound to this address accepts a connection to this destination: the same address, or
     * for a wildcard bind any address of this host's
     */
    static boolean accepts(InetSocketAddress bound, InetSocketAddress destination) {
        InetAddress address = destination.getAddress();
        if (address == null || bound.getPort() != destination.getPort()) {
            return false;
        }
        InetAddress boundAddress = bound.getAddress();
        if (boundAddress == null || boundAddress.isAnyLocalAddress()) {
            return isAddressOfThisHost(address);
        }
        return boundAddress.equals(address);
    }

    private static boolean isAddressOfThisHost(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
            return true;
        }
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException unknown) {
            return false;
        }
    }
}

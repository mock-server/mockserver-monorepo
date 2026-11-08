package org.mockserver.netty.proxy.relay;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;

import java.net.SocketAddress;
import java.util.UUID;

/**
 * Stands in for the relay's end of a CONNECT/SOCKS loopback connection, for a test about MockServer's end of it: while
 * open, a connection accepted from {@link #address()} on {@link #listenerAddress()} is taken to be one of this JVM's
 * relays' loopback legs. The addresses are unique, because the registry is JVM-wide and tests run in parallel.
 */
public final class RelayLoopbackStandIn implements AutoCloseable {

    private final SocketAddress address = new LocalAddress("relay-loopback-" + UUID.randomUUID());
    private final SocketAddress listenerAddress = new LocalAddress("mockserver-" + UUID.randomUUID());
    private final EmbeddedChannel proxyClient = new EmbeddedChannel();
    private final EmbeddedChannel relayLoopback = new EmbeddedChannel() {
        @Override
        protected SocketAddress localAddress0() {
            return address;
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return listenerAddress;
        }
    };

    public RelayLoopbackStandIn() {
        RelayLoopbackAddresses.register(relayLoopback, proxyClient);
    }

    /**
     * @return the remote address MockServer's end of this loopback has
     */
    public SocketAddress address() {
        return address;
    }

    /**
     * @return the local address MockServer's end of this loopback has
     */
    public SocketAddress listenerAddress() {
        return listenerAddress;
    }

    @Override
    public void close() {
        relayLoopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }
}

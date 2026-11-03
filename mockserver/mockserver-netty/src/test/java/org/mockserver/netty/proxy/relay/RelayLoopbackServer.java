package org.mockserver.netty.proxy.relay;

import org.mockserver.lifecycle.LifeCycle;

import java.net.InetAddress;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The server a relay under test opens its loopback to: {@link RelayConnectHandler} always connects to its own server's
 * port, so a test that plays MockServer's side of the loopback names its own listener's port here.
 */
final class RelayLoopbackServer {

    private RelayLoopbackServer() {
    }

    /**
     * @param port a listener on {@link InetAddress#getLoopbackAddress()}
     */
    static LifeCycle listeningOn(int port) {
        LifeCycle server = mock(LifeCycle.class);
        when(server.getLocalPort()).thenReturn(port);
        when(server.getLocalBoundAddress()).thenReturn(InetAddress.getLoopbackAddress());
        return server;
    }
}

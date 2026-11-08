package org.mockserver.lifecycle;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Assume;
import org.junit.Test;
import org.mockserver.netty.MockServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

public class OwnListenerTest {

    private static final int PORT = 1080;

    private static InetSocketAddress at(String address, int port) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(address), port);
    }

    @Test
    public void shouldTakeAnyAddressOfThisHostOnTheSamePortForAWildcardListener() throws Exception {
        for (InetSocketAddress wildcard : new InetSocketAddress[]{at("0.0.0.0", PORT), at("::", PORT)}) {
            assertThat(wildcard + " loopback", OwnListener.accepts(wildcard, at("127.0.0.1", PORT)), is(true));
            assertThat(wildcard + " another loopback address", OwnListener.accepts(wildcard, at("127.0.0.2", PORT)), is(true));
            assertThat(wildcard + " IPv6 loopback", OwnListener.accepts(wildcard, at("::1", PORT)), is(true));
            assertThat(wildcard + " the wildcard itself", OwnListener.accepts(wildcard, at("0.0.0.0", PORT)), is(true));
            assertThat(wildcard + " another port", OwnListener.accepts(wildcard, at("127.0.0.1", PORT + 1)), is(false));
            assertThat(wildcard + " another host", OwnListener.accepts(wildcard, at("192.0.2.1", PORT)), is(false));
        }
    }

    @Test
    public void shouldTakeAnAddressOfOneOfThisHostsInterfacesForAWildcardListener() throws Exception {
        InetAddress interfaceAddress = nonLoopbackInterfaceAddress();
        Assume.assumeTrue("this host has an interface address other than loopback", interfaceAddress != null);
        assertThat(OwnListener.accepts(at("0.0.0.0", PORT), new InetSocketAddress(interfaceAddress, PORT)), is(true));
    }

    @Test
    public void shouldTakeOnlyTheBoundAddressForAListenerBoundToOne() throws Exception {
        InetSocketAddress bound = at("127.0.0.1", PORT);
        assertThat(OwnListener.accepts(bound, at("127.0.0.1", PORT)), is(true));
        assertThat("another address of this host", OwnListener.accepts(bound, at("127.0.0.2", PORT)), is(false));
        assertThat("another port", OwnListener.accepts(bound, at("127.0.0.1", PORT + 1)), is(false));
    }

    @Test
    public void shouldNotTakeADestinationNotYetResolved() throws Exception {
        assertThat(OwnListener.accepts(at("0.0.0.0", PORT), InetSocketAddress.createUnresolved("localhost", PORT)), is(false));
    }

    @Test
    public void shouldAskTheServerThatAcceptedTheConnection() throws Exception {
        LifeCycle server = mock(LifeCycle.class);
        InetSocketAddress own = at("127.0.0.1", PORT);
        when(server.isListeningOn(own)).thenReturn(true);
        EmbeddedChannel remembered = new EmbeddedChannel();
        EmbeddedChannel notRemembered = new EmbeddedChannel();
        try {
            OwnListener.remember(remembered, server);
            OwnListener.remember(notRemembered, null);

            assertThat(OwnListener.isOwnListener(remembered, own), is(true));
            assertThat(OwnListener.isOwnListener(remembered, at("127.0.0.1", PORT + 1)), is(false));
            assertThat("a connection with no server remembered", OwnListener.isOwnListener(notRemembered, own), is(false));
        } finally {
            remembered.finishAndReleaseAll();
            notRemembered.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldKnowTheAddressesAndPortsARunningServerListensOn() throws Exception {
        MockServer server = new MockServer(configuration(), 0, 0);
        int port = server.getLocalPort();
        try (ServerSocket other = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            for (int bound : server.getLocalPorts()) {
                assertThat("port " + bound, server.isListeningOn(at("127.0.0.1", bound)), is(true));
            }
            assertThat("a port it does not listen on", server.isListeningOn(at("127.0.0.1", other.getLocalPort())), is(false));
            assertThat("a destination not yet resolved", server.isListeningOn(InetSocketAddress.createUnresolved("localhost", port)), is(false));
        } finally {
            server.stop();
        }
        assertThat("once stopped", server.isListeningOn(at("127.0.0.1", port)), is(false));
    }

    private static InetAddress nonLoopbackInterfaceAddress() throws SocketException {
        for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!address.isLinkLocalAddress()) {
                        return address;
                    }
                }
            }
        }
        return null;
    }
}

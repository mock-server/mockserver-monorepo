package org.mockserver.netty.proxy;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.model.HttpRequest;

import java.net.InetSocketAddress;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.model.HttpRequest.request;

public class ProxiedDestinationTest {

    private static final int MOCKSERVER_PORT = 1080;

    private final LifeCycle server = server();

    private static LifeCycle server() {
        LifeCycle server = mock(LifeCycle.class);
        when(server.isListeningOnPort(MOCKSERVER_PORT)).thenReturn(true);
        when(server.isListeningOn(any())).thenAnswer(invocation -> {
            InetSocketAddress destination = invocation.getArgument(0);
            return destination.getPort() == MOCKSERVER_PORT && destination.getAddress() != null && destination.getAddress().isLoopbackAddress();
        });
        return server;
    }

    @Test
    public void aNameAtAPortMockServerDoesNotListenOnIsAnotherHost() {
        assertThat(ProxiedDestination.isAnotherHost(server, InetSocketAddress.createUnresolved("upstream", 8080)), is(true));
        assertThat(ProxiedDestination.isAnotherHost(server, new InetSocketAddress("192.0.2.20", 8080)), is(true));
    }

    @Test
    public void aNameAtAListeningPortIsAmbiguousSoIsMockServer() {
        assertThat(ProxiedDestination.isAnotherHost(server, InetSocketAddress.createUnresolved("mockserver", MOCKSERVER_PORT)), is(false));
    }

    @Test
    public void anAddressNoListenerAcceptsAtAListeningPortIsAnotherHost() {
        assertThat(ProxiedDestination.isAnotherHost(server, new InetSocketAddress("192.0.2.20", MOCKSERVER_PORT)), is(true));
    }

    @Test
    public void anAddressAListenerAcceptsIsMockServer() {
        assertThat(ProxiedDestination.isAnotherHost(server, new InetSocketAddress("127.0.0.1", MOCKSERVER_PORT)), is(false));
    }

    @Test
    public void loopbackAtAnotherPortMayBeRelayedToMockServerSoIsMockServer() {
        assertThat(ProxiedDestination.isAnotherHost(server, InetSocketAddress.createUnresolved("localhost", 8080)), is(false));
        assertThat(ProxiedDestination.isAnotherHost(server, InetSocketAddress.createUnresolved("app.localhost", 8080)), is(false));
        assertThat(ProxiedDestination.isAnotherHost(server, new InetSocketAddress("127.0.0.1", 8080)), is(false));
        assertThat(ProxiedDestination.isAnotherHost(server, new InetSocketAddress("::1", 8080)), is(false));
        assertThat(ProxiedDestination.isAnotherHost(server, new InetSocketAddress("0.0.0.0", 8080)), is(false));
    }

    @Test
    public void loopbackAtAListeningPortNoListenerAcceptsIsStillMockServer() {
        LifeCycle boundToOneAddress = mock(LifeCycle.class);
        when(boundToOneAddress.isListeningOnPort(MOCKSERVER_PORT)).thenReturn(true);
        when(boundToOneAddress.isListeningOn(any())).thenReturn(false);

        assertThat(ProxiedDestination.isAnotherHost(boundToOneAddress, new InetSocketAddress("127.0.0.1", MOCKSERVER_PORT)), is(false));
    }

    @Test
    public void withNoServerOrDestinationNothingIsAnotherHost() {
        assertThat(ProxiedDestination.isAnotherHost(null, InetSocketAddress.createUnresolved("upstream", 8080)), is(false));
        assertThat(ProxiedDestination.isAnotherHost(server, null), is(false));
    }

    @Test
    public void onlyAnAbsoluteFormRequestIsClassifiedByItsHost() {
        HttpRequest originForm = request("/mockserver/clear").withHeader("Host", "upstream:8080");
        HttpRequest absoluteForm = request("/mockserver/clear").withHeader("Host", "upstream:8080").withAbsoluteFormRequestTarget(true);
        HttpRequest absoluteFormToMockServer = request("/mockserver/clear").withHeader("Host", "mockserver:" + MOCKSERVER_PORT).withAbsoluteFormRequestTarget(true);
        HttpRequest absoluteFormDefaultPort = request("/mockserver/clear").withHeader("Host", "upstream").withAbsoluteFormRequestTarget(true);
        HttpRequest absoluteFormUnparsableHost = request("/mockserver/clear").withHeader("Host", "upstream:notaport").withAbsoluteFormRequestTarget(true);

        assertThat(ProxiedDestination.absoluteFormToAnotherHost(server, originForm), is(false));
        assertThat(ProxiedDestination.absoluteFormToAnotherHost(server, absoluteForm), is(true));
        assertThat(ProxiedDestination.absoluteFormToAnotherHost(server, absoluteFormToMockServer), is(false));
        assertThat(ProxiedDestination.absoluteFormToAnotherHost(server, absoluteFormDefaultPort), is(true));
        assertThat(ProxiedDestination.absoluteFormToAnotherHost(server, absoluteFormUnparsableHost), is(false));
    }

    @Test
    public void aTunnelRemembersWhetherItsDestinationIsAnotherHost() {
        EmbeddedChannel toUpstream = new EmbeddedChannel();
        EmbeddedChannel toMockServer = new EmbeddedChannel();
        try {
            ProxiedDestination.rememberTunnelDestination(toUpstream, server, InetSocketAddress.createUnresolved("upstream", 443));
            ProxiedDestination.rememberTunnelDestination(toMockServer, server, new InetSocketAddress("127.0.0.1", MOCKSERVER_PORT));

            assertThat(ProxiedDestination.tunnelledToAnotherHost(toUpstream), is(true));
            assertThat(ProxiedDestination.tunnelledToAnotherHost(toMockServer), is(false));
        } finally {
            toUpstream.finishAndReleaseAll();
            toMockServer.finishAndReleaseAll();
        }
    }
}

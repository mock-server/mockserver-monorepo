package org.mockserver.netty.proxy.relay;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockserver.socket.SocketAddresses;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * MockServer's end of a relay loopback takes its client address from the tunnel's client, as MockServer itself knows
 * that client, and from nothing else.
 */
public class RelayLoopbackAddressesClientAddressTest {

    // the registry is JVM-wide and tests run in parallel: each loopback here has a port no other test uses
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(40_000 + (int) (Math.random() * 10_000));
    private static final InetSocketAddress LISTENER = new InetSocketAddress("127.0.0.1", 1);

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @After
    public void releaseChannels() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    }

    @Test
    public void shouldTakeTheClientAddressOfTheTunnelsClient() {
        InetSocketAddress client = new InetSocketAddress("192.0.2.5", 12345);
        EmbeddedChannel proxyClient = channel(LISTENER, client);

        EmbeddedChannel accepted = acceptedLoopbackOf(proxyClient);

        assertThat(SocketAddresses.clientAddress(accepted), is(client));
    }

    @Test
    public void shouldTakeTheSourceOfAProxyProtocolHeaderAcceptedOnTheTunnelsClientConnection() {
        InetSocketAddress source = new InetSocketAddress("198.51.100.9", 40000);
        EmbeddedChannel proxyClient = channel(LISTENER, new InetSocketAddress("127.0.0.1", 50000));
        proxyClient.attr(SocketAddresses.PROXY_PROTOCOL_SOURCE).set(source);

        EmbeddedChannel accepted = acceptedLoopbackOf(proxyClient);

        assertThat(SocketAddresses.clientAddress(accepted), is(source));
    }

    @Test
    public void shouldKeepTheOriginalClientAddressThroughATunnelOpenedInsideATunnel() {
        InetSocketAddress client = new InetSocketAddress("192.0.2.5", 12345);
        EmbeddedChannel proxyClient = channel(LISTENER, client);

        EmbeddedChannel accepted = proxyClient;
        for (int depth = 0; depth < 3; depth++) {
            accepted = acceptedLoopbackOf(accepted);
        }

        assertThat(SocketAddresses.clientAddress(accepted), is(client));
    }

    @Test
    public void shouldKeepTheClientAddressOnceTheRelaysEndOfTheLoopbackHasClosed() {
        InetSocketAddress client = new InetSocketAddress("192.0.2.5", 12345);
        EmbeddedChannel proxyClient = channel(LISTENER, client);
        InetSocketAddress relayEnd = loopbackAddress();
        EmbeddedChannel relayLoopback = channel(relayEnd, LISTENER);
        RelayLoopbackAddresses.register(relayLoopback, proxyClient);
        EmbeddedChannel accepted = channel(LISTENER, relayEnd);
        RelayLoopbackAddresses.rememberProxyClient(accepted);

        relayLoopback.close();

        assertThat(SocketAddresses.clientAddress(accepted), is(client));
    }

    @Test
    public void shouldNotGiveAClientAddressToAConnectionThatIsNotARelayLoopback() {
        EmbeddedChannel proxyClient = channel(LISTENER, new InetSocketAddress("192.0.2.5", 12345));
        InetSocketAddress relayEnd = loopbackAddress();
        RelayLoopbackAddresses.register(channel(relayEnd, LISTENER), proxyClient);
        // the same source port to another listener, and the relay's source after its loopback has closed
        EmbeddedChannel toAnotherListener = channel(new InetSocketAddress("127.0.0.1", 2), relayEnd);
        InetSocketAddress closedRelayEnd = loopbackAddress();
        EmbeddedChannel closedLoopback = channel(closedRelayEnd, LISTENER);
        RelayLoopbackAddresses.register(closedLoopback, proxyClient);
        closedLoopback.close();
        EmbeddedChannel fromAClosedRelay = channel(LISTENER, closedRelayEnd);

        for (EmbeddedChannel notARelay : List.of(toAnotherListener, fromAClosedRelay)) {
            RelayLoopbackAddresses.rememberProxyClient(notARelay);

            assertThat(notARelay.attr(SocketAddresses.PROXY_PROTOCOL_SOURCE).get(), is(nullValue()));
            assertThat(SocketAddresses.clientAddress(notARelay), is(notARelay.remoteAddress()));
        }
    }

    @Test
    public void shouldRegisterNothingForALoopbackWithoutAnAddress() {
        EmbeddedChannel proxyClient = channel(LISTENER, new InetSocketAddress("192.0.2.5", 12345));
        RelayLoopbackAddresses.register(channel(null, LISTENER), proxyClient);
        EmbeddedChannel accepted = channel(LISTENER, null);

        assertThat(RelayLoopbackAddresses.isRelayLoopback(accepted), is(false));
        RelayLoopbackAddresses.rememberProxyClient(accepted);
        assertThat(accepted.attr(SocketAddresses.PROXY_PROTOCOL_SOURCE).get(), is(nullValue()));
    }

    @Test
    public void shouldLeaveTheLoopbacksPeerAsTheClientAddressWhenTheTunnelsClientHasNoIpAddress() {
        EmbeddedChannel proxyClient = new EmbeddedChannel();
        channels.add(proxyClient);

        EmbeddedChannel accepted = acceptedLoopbackOf(proxyClient);

        assertThat(accepted.attr(SocketAddresses.PROXY_PROTOCOL_SOURCE).get(), is(nullValue()));
        assertThat(SocketAddresses.clientAddress(accepted), is(accepted.remoteAddress()));
    }

    /** MockServer's end of a loopback a relay opened for a tunnel of this proxy client, accepted as port unification does. */
    private EmbeddedChannel acceptedLoopbackOf(EmbeddedChannel proxyClient) {
        InetSocketAddress relayEnd = loopbackAddress();
        RelayLoopbackAddresses.register(channel(relayEnd, LISTENER), proxyClient);
        EmbeddedChannel accepted = channel(LISTENER, relayEnd);
        assertThat(RelayLoopbackAddresses.isRelayLoopback(accepted), is(true));
        RelayLoopbackAddresses.rememberProxyClient(accepted);
        return accepted;
    }

    private static InetSocketAddress loopbackAddress() {
        return new InetSocketAddress("127.0.0.1", NEXT_PORT.getAndIncrement());
    }

    private EmbeddedChannel channel(SocketAddress local, SocketAddress remote) {
        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override
            protected SocketAddress localAddress0() {
                return local;
            }

            @Override
            protected SocketAddress remoteAddress0() {
                return remote;
            }
        };
        channels.add(channel);
        return channel;
    }
}

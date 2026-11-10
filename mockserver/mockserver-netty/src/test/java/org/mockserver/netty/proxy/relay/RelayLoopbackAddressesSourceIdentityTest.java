package org.mockserver.netty.proxy.relay;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.mockserver.netty.controlplane.ControlPlaneListenerSettings;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;

public class RelayLoopbackAddressesSourceIdentityTest {
    private final ControlPlaneListenerSettings policy = new ControlPlaneListenerSettings(
        configuration().controlPlaneAllowedCidrs("127.0.0.1/32"));

    @Test
    public void shouldPreserveAllowedAndDeniedSourcesAcrossManyRegisteredRelayLegs() {
        for (String host : new String[]{"127.0.0.1", "192.0.2.5"}) {
            InetSocketAddress original = new InetSocketAddress(host, 12345);
            assertRelayChain(original, host.equals("127.0.0.1"));
        }
    }

    @Test
    public void shouldKeepUnknownSourceExplicitAndDeniedAcrossRegisteredRelayLegs() {
        assertRelayChain(null, false);
    }

    private void assertRelayChain(InetSocketAddress original, boolean allowed) {
        AddressedChannel client = new AddressedChannel(null, original);
        try {
            for (int depth = 0; depth < 128; depth++) {
                InetSocketAddress relayAddress = new InetSocketAddress("127.0.0.1", 20000 + depth);
                AddressedChannel outgoing = new AddressedChannel(relayAddress, new InetSocketAddress("127.0.0.1", 1080));
                AddressedChannel accepted = new AddressedChannel(new InetSocketAddress("127.0.0.1", 1080), relayAddress);
                try {
                    RelayLoopbackAddresses.register(outgoing, client, policy.source(client));
                    assertThat(RelayLoopbackAddresses.isRelayLoopback(accepted), is(true));
                    assertThat(RelayLoopbackAddresses.sourceIdentityOf(accepted), notNullValue());
                    assertThat(policy.source(accepted), is(original));
                    assertThat(policy.allowed(policy.source(accepted)), is(allowed));
                    outgoing.finishAndReleaseAll();
                    assertThat(RelayLoopbackAddresses.isRelayLoopback(accepted), is(false));
                    assertThat(policy.source(accepted), is(original));
                } finally {
                    outgoing.finishAndReleaseAll();
                    client.finishAndReleaseAll();
                }
                client = accepted;
            }
        } finally {
            client.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldRemoveRegistrationWithoutLosingCapturedAcceptedIdentity() {
        InetSocketAddress original = new InetSocketAddress("192.0.2.5", 12345);
        InetSocketAddress relayAddress = new InetSocketAddress("127.0.0.1", 21000);
        AddressedChannel client = new AddressedChannel(null, original);
        AddressedChannel outgoing = new AddressedChannel(relayAddress, new InetSocketAddress("127.0.0.1", 1080));
        AddressedChannel accepted = new AddressedChannel(new InetSocketAddress("127.0.0.1", 1080), relayAddress);
        AddressedChannel unrelated = new AddressedChannel(new InetSocketAddress("127.0.0.1", 1080), relayAddress);
        try {
            RelayLoopbackAddresses.register(outgoing, client, original);
            assertThat(policy.source(accepted), is(original));
            outgoing.finishAndReleaseAll();
            assertThat(RelayLoopbackAddresses.proxyClientOf(accepted), nullValue());
            assertThat(RelayLoopbackAddresses.sourceIdentityOf(unrelated), nullValue());
            assertThat(policy.source(accepted), is(original));
            assertThat(policy.allowed(policy.source(accepted)), is(false));
        } finally {
            outgoing.finishAndReleaseAll();
            accepted.finishAndReleaseAll();
            unrelated.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    private static final class AddressedChannel extends EmbeddedChannel {
        private final SocketAddress local;
        private final SocketAddress remote;

        private AddressedChannel(SocketAddress local, SocketAddress remote) {
            this.local = local;
            this.remote = remote;
        }

        @Override
        protected SocketAddress localAddress0() {
            return local;
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return remote;
        }
    }
}

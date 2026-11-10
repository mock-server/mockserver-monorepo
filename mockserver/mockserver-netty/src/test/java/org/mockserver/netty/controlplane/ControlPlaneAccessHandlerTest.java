package org.mockserver.netty.controlplane;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

public class ControlPlaneAccessHandlerTest {
    @Test
    public void closesManagementConnectionOutsideAllowedNetworks() {
        ControlPlaneListenerSettings settings = new ControlPlaneListenerSettings(
            configuration().controlPlanePort(0).controlPlaneAllowedCidrs("192.0.2.0/24"));
        EmbeddedChannel channel = new EmbeddedChannel(new ControlPlaneAccessHandler(settings));
        assertThat(channel.isActive(), is(false));
        channel.finishAndReleaseAll();
    }

    @Test
    public void allowsConnectionWhenNoSourceRestrictionIsConfigured() {
        EmbeddedChannel channel = new EmbeddedChannel(new ControlPlaneAccessHandler(
            new ControlPlaneListenerSettings(configuration())));
        assertThat(channel.isActive(), is(true));
        channel.finishAndReleaseAll();
    }
}

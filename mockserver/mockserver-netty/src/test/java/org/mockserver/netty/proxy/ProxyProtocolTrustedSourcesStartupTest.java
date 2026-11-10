package org.mockserver.netty.proxy;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.RefusedStart.RecordingMockServer;
import org.mockserver.lifecycle.RefusedStart.Started;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockserver.lifecycle.RefusedStart.refusedStart;

/**
 * A {@code proxyProtocolTrustedSources} value that does not parse refuses the start, as it is a security boundary; the
 * setters refuse such a value, so it is given as a system property, environment variable or properties file would
 * give it, without their validation.
 */
public class ProxyProtocolTrustedSourcesStartupTest {

    @Test
    public void shouldRefuseToStartWithAnEntryThatIsNotAnIpAddressOrCidrBlock() throws Exception {
        Started started = new Started();
        Configuration unvalidated = new Configuration() {
            @Override
            public String proxyProtocolTrustedSources() {
                return "10.0.1.0/24, lb.example.com";
            }
        };

        Throwable refused = refusedStart(() -> new RecordingMockServer(started, unvalidated.transparentProxyEnabled(true)));

        assertThat(refused, instanceOf(IllegalArgumentException.class));
        assertThat(refused.getMessage(), is("proxyProtocolTrustedSources entry \"lb.example.com\" is not an IP address or CIDR block"));
        assertThat("refused before any port was bound", started.tcpPorts, is(empty()));
    }

    @Test
    public void shouldRefuseToStartWithAnEntryWiderThanItLooksEvenWhileTransparentProxyingIsOff() throws Exception {
        Started started = new Started();
        Configuration unvalidated = new Configuration() {
            @Override
            public String proxyProtocolTrustedSources() {
                return "10.0.1.17/8";
            }
        };

        Throwable refused = refusedStart(() -> new RecordingMockServer(started, unvalidated));

        assertThat(refused, instanceOf(IllegalArgumentException.class));
        assertThat(refused.getMessage(), is("proxyProtocolTrustedSources entry \"10.0.1.17/8\" has bits set after its prefix length, so it is wider than it looks"));
        assertThat("refused before any port was bound", started.tcpPorts, is(empty()));
    }
}

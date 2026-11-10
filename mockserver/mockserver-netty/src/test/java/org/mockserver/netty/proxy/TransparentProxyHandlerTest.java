package org.mockserver.netty.proxy;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;
import static org.mockserver.netty.proxy.TransparentProxyHandler.TRANSPARENT_ORIGINAL_DST_RESOLVED;

public class TransparentProxyHandlerTest {

    private final MockServerLogger logger = new MockServerLogger(TransparentProxyHandlerTest.class);

    @Test
    public void shouldSetRemoteSocketWhenOriginalDstResolved() {
        // given
        Configuration configuration = new Configuration();
        configuration.transparentProxyEnabled(true);
        InetSocketAddress originalDst = new InetSocketAddress("93.184.216.34", 80);

        TransparentProxyHandler.OriginalDestinationResolver resolver = channel -> originalDst;
        TransparentProxyHandler handler = new TransparentProxyHandler(configuration, logger, resolver);

        // when -- EmbeddedChannel triggers channelActive
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // then
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(originalDst));
        assertThat(channel.attr(PROXYING).get(), is(Boolean.TRUE));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.TRUE));

        channel.close();
    }

    @Test
    public void shouldFallBackToHostHeaderWhenOriginalDstReturnsNull() {
        // given
        Configuration configuration = new Configuration();
        configuration.transparentProxyEnabled(true);

        TransparentProxyHandler.OriginalDestinationResolver resolver = channel -> null;
        TransparentProxyHandler handler = new TransparentProxyHandler(configuration, logger, resolver);

        // when
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // then -- no REMOTE_SOCKET set, but PROXYING is true (Host header path)
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.attr(PROXYING).get(), is(Boolean.TRUE));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.FALSE));

        channel.close();
    }

    @Test
    public void shouldFallBackToHostHeaderWhenUnsupportedOperationException() {
        // given
        Configuration configuration = new Configuration();
        configuration.transparentProxyEnabled(true);

        TransparentProxyHandler.OriginalDestinationResolver resolver = channel -> {
            throw new UnsupportedOperationException("SO_ORIGINAL_DST requires Linux");
        };
        TransparentProxyHandler handler = new TransparentProxyHandler(configuration, logger, resolver);

        // when
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // then -- graceful fallback
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.attr(PROXYING).get(), is(Boolean.TRUE));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.FALSE));

        channel.close();
    }

    @Test
    public void shouldFallBackToHostHeaderOnUnexpectedException() {
        // given
        Configuration configuration = new Configuration();
        configuration.transparentProxyEnabled(true);

        TransparentProxyHandler.OriginalDestinationResolver resolver = channel -> {
            throw new RuntimeException("conntrack read failed");
        };
        TransparentProxyHandler handler = new TransparentProxyHandler(configuration, logger, resolver);

        // when
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // then -- graceful fallback
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.attr(PROXYING).get(), is(Boolean.TRUE));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.FALSE));

        channel.close();
    }

    @Test
    public void shouldBeNoOpWhenTransparentProxyDisabled() {
        // given
        Configuration configuration = new Configuration();
        // transparentProxyEnabled defaults to false

        TransparentProxyHandler.OriginalDestinationResolver resolver = channel -> {
            throw new AssertionError("resolver should not be called when disabled");
        };
        TransparentProxyHandler handler = new TransparentProxyHandler(configuration, logger, resolver);

        // when
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // then -- nothing set
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.attr(PROXYING).get(), is(nullValue()));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(nullValue()));

        channel.close();
    }

    @Test
    public void shouldWarnAtStartupWhenTransparentProxyAndProxyAuthenticationAreBothConfigured() {
        CapturingLogger capturingLogger = new CapturingLogger();

        TransparentProxyHandler.warnIfProxyAuthenticationConfigured(configuration(true, "user", "secret-password"), capturingLogger);

        assertThat(capturingLogger.logged.size(), is(1));
        LogEntry entry = capturingLogger.logged.get(0);
        assertThat(entry.getLogLevel(), is(Level.WARN));
        assertThat(entry.getType(), is(LogEntry.LogMessageType.SERVER_CONFIGURATION));
        assertThat(entry.getMessage(), containsString("not challenged for proxy credentials"));
        assertThat(entry.getMessage(), containsString("PROXY protocol header"));
        assertThat(entry.getMessage(), containsString("set proxyProtocolTrustedSources to accept PROXY protocol headers only from your load balancer"));
        assertThat("the credentials are not logged", entry.getMessage(), not(containsString("secret-password")));
    }

    @Test
    public void shouldSayInTheStartupWarningThatTheHostHeaderFallbackStaysOpenWhenProxyProtocolTrustedSourcesIsSet() {
        CapturingLogger capturingLogger = new CapturingLogger();

        TransparentProxyHandler.warnIfProxyAuthenticationConfigured(configuration(true, "user", "secret-password").proxyProtocolTrustedSources("10.0.0.0/8"), capturingLogger);

        assertThat(capturingLogger.logged.size(), is(1));
        assertThat(capturingLogger.logged.get(0).getMessage(), containsString("proxyProtocolTrustedSources accepts PROXY protocol headers only from the sources it lists, but the Host header fallback is open to any client"));
    }

    @Test
    public void shouldNotWarnAtStartupUnlessTransparentProxyAndProxyAuthenticationAreBothConfigured() {
        CapturingLogger capturingLogger = new CapturingLogger();

        TransparentProxyHandler.warnIfProxyAuthenticationConfigured(configuration(false, "user", "password"), capturingLogger);
        TransparentProxyHandler.warnIfProxyAuthenticationConfigured(configuration(true, "", ""), capturingLogger);
        TransparentProxyHandler.warnIfProxyAuthenticationConfigured(configuration(true, "user", ""), capturingLogger);
        TransparentProxyHandler.warnIfProxyAuthenticationConfigured(configuration(false, "", ""), capturingLogger);

        assertThat(capturingLogger.logged.size(), is(0));
    }

    private static Configuration configuration(boolean transparentProxyEnabled, String username, String password) {
        Configuration configuration = new Configuration();
        configuration.transparentProxyEnabled(transparentProxyEnabled);
        configuration.proxyAuthenticationUsername(username);
        configuration.proxyAuthenticationPassword(password);
        return configuration;
    }

    private static final class CapturingLogger extends MockServerLogger {
        private final List<LogEntry> logged = new CopyOnWriteArrayList<>();

        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    }
}

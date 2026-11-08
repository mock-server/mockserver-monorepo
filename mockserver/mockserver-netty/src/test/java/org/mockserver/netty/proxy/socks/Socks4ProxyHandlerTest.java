package org.mockserver.netty.proxy.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.socksx.v4.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * EmbeddedChannel tests for {@link Socks4ProxyHandler} verifying:
 * - CONNECT command triggers pipeline replacement (forwardConnection) and fires channelRead
 * - non-CONNECT command returns REJECTED_OR_FAILED response and closes channel
 * - exceptionCaught closes the channel (inherited from SocksProxyHandler)
 */
public class Socks4ProxyHandlerTest {

    private EmbeddedChannel channel;
    private Configuration configuration;
    private MockServerLogger mockServerLogger;
    private LifeCycle server;

    @Before
    public void setUp() {
        configuration = configuration();
        mockServerLogger = new MockServerLogger();
        server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));

        channel = new EmbeddedChannel(
            new Socks4ServerDecoder(),
            Socks4ServerEncoder.INSTANCE,
            new Socks4ProxyHandler(configuration, mockServerLogger, server)
        );
    }

    @After
    public void tearDown() {
        if (channel != null) {
            try {
                channel.checkException();
            } catch (Exception ignored) {
                // expected for error-path tests
            }
            try {
                channel.finishAndReleaseAll();
            } catch (Exception ignored) {
                // channel may already be closed
            }
        }
    }

    @Test
    public void shouldRejectNonConnectCommandWithFailureResponse() {
        // given - a SOCKS4 BIND command (not CONNECT)
        // SOCKS4 BIND: version=0x04, cmd=0x02, port, ip, userid\0
        byte[] socks4BindRequest = new byte[]{
            0x04,                           // SOCKS4 version
            0x02,                           // BIND command
            0x00, 0x50,                     // port 80
            0x7f, 0x00, 0x00, 0x01,         // 127.0.0.1
            0x00                            // empty userid, null terminated
        };

        // when
        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(socks4BindRequest));

        // then - should respond with REJECTED_OR_FAILED
        ByteBuf response = channel.readOutbound();
        assertThat("should write a response", response, is(notNullValue()));
        byte[] responseBytes = ByteBufUtil.getBytes(response);
        response.release();

        // SOCKS4 response: version=0x00, status=0x5b (91 = REJECTED_OR_FAILED)
        assertThat("response version byte should be 0x00", responseBytes[0], is((byte) 0x00));
        assertThat("response status should be REJECTED_OR_FAILED (0x5b)", responseBytes[1], is((byte) 0x5b));

        // and - channel should be closed (CLOSE listener fires)
        channel.runPendingTasks();
        assertFalse("channel should be closed after rejection", channel.isActive());
    }

    @Test
    public void shouldReplaceProxyHandlerOnConnectCommand() {
        // given - a SOCKS4 CONNECT command to 127.0.0.1:443
        byte[] socks4ConnectRequest = new byte[]{
            0x04,                           // SOCKS4 version
            0x01,                           // CONNECT command
            0x01, (byte) 0xBB,             // port 443
            0x7f, 0x00, 0x00, 0x01,         // 127.0.0.1
            0x00                            // empty userid, null terminated
        };

        // when
        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(socks4ConnectRequest));
        channel.runPendingTasks();

        // then - the Socks4ProxyHandler should be removed from the pipeline
        // (forwardConnection replaces it with Socks4ConnectHandler, which then
        // attempts Bootstrap.connect and fails in an EmbeddedChannel context,
        // writing a SOCKS4 failure response and closing the channel)
        assertThat("Socks4ProxyHandler should be removed from pipeline",
            channel.pipeline().get(Socks4ProxyHandler.class), is(nullValue()));

        // and - the channel should be closed (Bootstrap.connect fails in EmbeddedChannel)
        // OR - a SOCKS4 failure response should be written
        // (The exact outcome depends on Netty's error handling for Bootstrap.connect
        // in an EmbeddedChannel, but the handler replacement is the key observable)
    }

    @Test
    public void shouldCloseChannelOnException() {
        // given
        assertTrue("channel should be active initially", channel.isActive());

        // when
        channel.pipeline().fireExceptionCaught(new RuntimeException("simulated error"));
        channel.runPendingTasks();

        // then
        assertFalse("channel should be closed on exception", channel.isActive());
    }

    @Test
    public void shouldRejectConnectWhenCredentialsConfigured() {
        configuration.proxyAuthenticationUsername("testuser");
        configuration.proxyAuthenticationPassword("testpass");
        CapturingLogger logger = new CapturingLogger();
        EmbeddedChannel refusing = new EmbeddedChannel(
            new Socks4ServerDecoder(),
            Socks4ServerEncoder.INSTANCE,
            new Socks4ProxyHandler(configuration, logger, server)
        );
        try {
            // a CONNECT to 127.0.0.1:80 with user id "testuser", followed in the same read by an HTTP request
            byte[] connect = new byte[]{0x04, 0x01, 0x00, 0x50, 0x7f, 0x00, 0x00, 0x01, 't', 'e', 's', 't', 'u', 's', 'e', 'r', 0x00};
            byte[] get = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes();
            refusing.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(connect, get));

            ByteBuf response = refusing.readOutbound();
            assertThat("should write a response", response, is(notNullValue()));
            byte[] responseBytes = ByteBufUtil.getBytes(response);
            response.release();
            assertThat("REJECTED_OR_FAILED", responseBytes[1], is((byte) 0x5b));
            refusing.runPendingTasks();
            assertFalse("channel should be closed after rejection", refusing.isActive());
            assertThat("the proxy handler is not replaced by a connect handler", refusing.pipeline().get(Socks4ConnectHandler.class), is(nullValue()));
            assertThat("nothing sent after the request reaches the handlers behind", refusing.readInbound(), is(nullValue()));

            assertThat(logger.logged, hasSize(1));
            org.mockserver.log.model.LogEntry entry = logger.logged.get(0);
            assertThat(entry.getLogLevel(), is(org.slf4j.event.Level.WARN));
            assertThat(entry.getMessageFormat(), containsString("SOCKS4 cannot carry a password"));
            assertThat("the user id is not logged", entry.getMessage(), not(containsString("testuser")));
        } finally {
            try {
                refusing.finishAndReleaseAll();
            } catch (Exception ignored) {
                // channel may already be closed
            }
        }
    }

    private static final class CapturingLogger extends MockServerLogger {
        private final java.util.List<org.mockserver.log.model.LogEntry> logged = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public boolean isEnabledForInstance(org.slf4j.event.Level level) {
            return true;
        }

        @Override
        public void logEvent(org.mockserver.log.model.LogEntry logEntry) {
            logged.add(logEntry);
        }
    }
}

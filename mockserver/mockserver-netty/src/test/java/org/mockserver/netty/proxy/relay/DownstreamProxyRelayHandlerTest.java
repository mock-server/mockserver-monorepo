package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.*;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * EmbeddedChannel tests for {@link DownstreamProxyRelayHandler} verifying:
 * - channelActive writes EMPTY_BUFFER to trigger pipeline activation
 * - channelRead0 relays HttpObject to the upstream channel
 * - channelInactive closes the upstream channel
 * - exceptionCaught closes the handler's own channel
 */
public class DownstreamProxyRelayHandlerTest {

    private EmbeddedChannel downstreamChannel;
    private EmbeddedChannel upstreamChannel;
    private MockServerLogger mockServerLogger;

    @Before
    public void setUp() {
        mockServerLogger = new MockServerLogger();
        upstreamChannel = new EmbeddedChannel();
        downstreamChannel = new EmbeddedChannel(new DownstreamProxyRelayHandler(mockServerLogger, upstreamChannel));
    }

    @After
    public void tearDown() {
        if (downstreamChannel != null) {
            try {
                downstreamChannel.checkException();
            } catch (Exception ignored) {
                // expected for exception-path tests
            }
            try {
                downstreamChannel.finishAndReleaseAll();
            } catch (Exception ignored) {
                // channel may already be closed
            }
        }
        if (upstreamChannel != null) {
            try {
                upstreamChannel.finishAndReleaseAll();
            } catch (Exception ignored) {
                // channel may already be closed
            }
        }
    }

    @Test
    public void shouldRelayHttpResponseToUpstreamChannel() {
        // given
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8)
        );
        response.retain(); // retain because handler does not auto-release (super(false))

        // when
        downstreamChannel.writeInbound(response);

        // then - the response should appear on the upstream channel's outbound
        Object relayed = upstreamChannel.readOutbound();
        assertThat("response should be relayed to upstream", relayed, is(notNullValue()));
        assertThat(relayed, instanceOf(DefaultFullHttpResponse.class));
        DefaultFullHttpResponse relayedResponse = (DefaultFullHttpResponse) relayed;
        assertThat(relayedResponse.status(), is(HttpResponseStatus.OK));
        relayedResponse.release();
    }

    @Test
    public void shouldRelayHttpContentChunksToUpstream() {
        // given - handler uses super(false) so no auto-release; retain for writeInbound
        DefaultHttpContent chunk = new DefaultHttpContent(Unpooled.copiedBuffer("chunk1", StandardCharsets.UTF_8));
        chunk.retain(); // one ref for writeInbound, one for upstream consumption

        // when
        downstreamChannel.writeInbound(chunk);

        // then
        Object relayedChunk = upstreamChannel.readOutbound();
        assertThat("chunk should be relayed", relayedChunk, is(notNullValue()));
        assertThat(relayedChunk, instanceOf(DefaultHttpContent.class));
        DefaultHttpContent relayedContent = (DefaultHttpContent) relayedChunk;
        assertThat(relayedContent.content().toString(StandardCharsets.UTF_8), is("chunk1"));
        relayedContent.release();
    }

    @Test
    public void shouldCloseUpstreamChannelOnChannelInactive() {
        // given - upstream is active
        assertTrue("upstream should be active initially", upstreamChannel.isActive());

        // when - downstream becomes inactive
        downstreamChannel.close();
        downstreamChannel.runPendingTasks();
        upstreamChannel.runPendingTasks();

        // then - upstream should be closed (closeOnFlush only closes if active)
        assertFalse("upstream should be closed when downstream goes inactive", upstreamChannel.isActive());
    }

    @Test
    public void shouldCloseOwnChannelOnException() {
        // given
        assertTrue("downstream should be active initially", downstreamChannel.isActive());

        // when
        downstreamChannel.pipeline().fireExceptionCaught(new RuntimeException("simulated error"));
        downstreamChannel.runPendingTasks();

        // then
        assertFalse("downstream channel should be closed on exception", downstreamChannel.isActive());
    }

    @Test
    public void shouldLogWarnAndCloseOnDecoderFault() {
        // given - a handler wired with a mock logger so the log level can be asserted
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, new EmbeddedChannel()));

        // when - a genuine decoder fault is caught
        channel.pipeline().fireExceptionCaught(new DecoderException("bad frame"));
        channel.runPendingTasks();

        // then - it is surfaced at WARN (not silently dropped) and the channel is closed
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.WARN));
        assertFalse("channel should be closed on decoder fault", channel.isActive());

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLogWarnAndCloseOnSslFault() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, new EmbeddedChannel()));

        // when - a throwable whose cause is an SSLException is caught
        channel.pipeline().fireExceptionCaught(new RuntimeException("relay failed", new SSLException("handshake failed")));
        channel.runPendingTasks();

        // then
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.WARN));
        assertFalse("channel should be closed on SSL fault", channel.isActive());

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldStaySilentOnBenignConnectionClose() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, new EmbeddedChannel()));

        // when - a benign connection reset is caught
        channel.pipeline().fireExceptionCaught(new RuntimeException("Connection reset by peer"));
        channel.runPendingTasks();

        // then - nothing is logged but the channel is still closed
        verify(logger, org.mockito.Mockito.never()).logEvent(org.mockito.ArgumentMatchers.any(LogEntry.class));
        assertFalse("channel should be closed on benign close", channel.isActive());

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLogErrorOnUnexpectedException() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(new DownstreamProxyRelayHandler(logger, new EmbeddedChannel()));

        // when - an unexpected exception is caught
        channel.pipeline().fireExceptionCaught(new IllegalStateException("unexpected"));
        channel.runPendingTasks();

        // then - it is surfaced at ERROR and the channel is closed
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.ERROR));
        assertFalse("channel should be closed on unexpected exception", channel.isActive());

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldMarkTheProxyClientClosedByTheServerForAnExceptionItClosesForButNotForABenignConnectionClose() throws Exception {
        for (Throwable cause : new Throwable[]{new IllegalStateException("unexpected"), new DecoderException("bad frame"), outOfDirectMemory()}) {
            EmbeddedChannel proxyClient = new EmbeddedChannel();
            EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(mock(MockServerLogger.class), proxyClient));

            loopback.pipeline().fireExceptionCaught(cause);

            assertThat(cause.toString(), ServerClosedChannels.isClosedByServer(proxyClient), is(true));
            loopback.finishAndReleaseAll();
            proxyClient.finishAndReleaseAll();
        }
        EmbeddedChannel proxyClient = new EmbeddedChannel();
        EmbeddedChannel loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(mock(MockServerLogger.class), proxyClient));

        loopback.pipeline().fireExceptionCaught(new RuntimeException("Connection reset by peer"));

        assertThat(ServerClosedChannels.isClosedByServer(proxyClient), is(false));
        loopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    private static Throwable outOfDirectMemory() throws Exception {
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("failed to allocate 4194304 byte(s) of direct memory");
    }

    @Test
    public void shouldWriteEmptyBufferOnChannelActive() {
        // The channelActive of the handler writes EMPTY_BUFFER — test by verifying
        // no exception and that the channel is still active after activation
        assertTrue("channel should be active after initialization", downstreamChannel.isActive());
        // The EMPTY_BUFFER write happens during channelActive; EmbeddedChannel fires it
        // on construction. checkException() throws if any exception was stored.
        downstreamChannel.checkException();
    }
}

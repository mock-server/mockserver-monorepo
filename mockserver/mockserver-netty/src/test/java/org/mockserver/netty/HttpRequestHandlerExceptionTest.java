package org.mockserver.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.lang.reflect.Constructor;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link HttpRequestHandler#exceptionCaught} routes exceptions to the correct log
 * level: ERROR for unexpected exceptions, WARN for genuine SSL/decoder faults (so they are not
 * silently dropped), and silent for benign connection closes.
 */
public class HttpRequestHandlerExceptionTest {

    private HttpRequestHandler handler(MockServerLogger logger) {
        HttpState httpState = mock(HttpState.class);
        when(httpState.getMockServerLogger()).thenReturn(logger);
        return new HttpRequestHandler(
            mock(Configuration.class),
            mock(LifeCycle.class),
            httpState,
            mock(HttpActionHandler.class)
        );
    }

    @Test
    public void shouldLogWarnOnDecoderFault() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(handler(logger));

        // when
        channel.pipeline().fireExceptionCaught(new DecoderException("bad frame"));
        channel.runPendingTasks();

        // then
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.WARN));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLogWarnOnSslFault() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(handler(logger));

        // when
        channel.pipeline().fireExceptionCaught(new RuntimeException("relay failed", new SSLException("handshake failed")));
        channel.runPendingTasks();

        // then
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.WARN));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldStaySilentOnBenignConnectionClose() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(handler(logger));

        // when
        channel.pipeline().fireExceptionCaught(new RuntimeException("Connection reset by peer"));
        channel.runPendingTasks();

        // then
        verify(logger, never()).logEvent(any(LogEntry.class));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldMarkTheChannelClosedByTheServerForAnExceptionItClosesForButNotForABenignConnectionClose() throws Exception {
        MockServerLogger logger = mock(MockServerLogger.class);
        for (Throwable cause : new Throwable[]{new IllegalStateException("unexpected"), new DecoderException("bad frame"), outOfDirectMemory()}) {
            EmbeddedChannel channel = new EmbeddedChannel(handler(logger));

            channel.pipeline().fireExceptionCaught(cause);

            assertThat(cause.toString(), ServerClosedChannels.isClosedByServer(channel), is(true));
            channel.finishAndReleaseAll();
        }
        EmbeddedChannel reset = new EmbeddedChannel(handler(logger));

        reset.pipeline().fireExceptionCaught(new RuntimeException("Connection reset by peer"));

        assertThat(ServerClosedChannels.isClosedByServer(reset), is(false));
        reset.finishAndReleaseAll();
    }

    private static Throwable outOfDirectMemory() throws Exception {
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("failed to allocate 4194304 byte(s) of direct memory");
    }

    @Test
    public void shouldLogErrorOnUnexpectedException() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel(handler(logger));

        // when
        channel.pipeline().fireExceptionCaught(new IllegalStateException("unexpected"));
        channel.runPendingTasks();

        // then
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.ERROR));

        channel.finishAndReleaseAll();
    }
}

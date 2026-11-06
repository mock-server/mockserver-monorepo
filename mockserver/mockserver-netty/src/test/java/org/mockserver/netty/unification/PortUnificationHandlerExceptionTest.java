package org.mockserver.netty.unification;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import javax.net.ssl.SSLHandshakeException;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * Verifies that {@link PortUnificationHandler#exceptionCaught} no longer silently drops a plain
 * {@link DecoderException}. SSLException-family faults are handled by the sslHandshakeException
 * branch; a plain decoder fault must now hit the isSslOrDecoderFault WARN branch.
 */
public class PortUnificationHandlerExceptionTest {

    @Test
    public void shouldLogWarnOnPlainDecoderFault() {
        // given - a handler whose logger is a mock so the log level can be asserted
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        HttpState httpState = mock(HttpState.class);
        when(httpState.getMockServerLogger()).thenReturn(logger);
        PortUnificationHandler handler = new PortUnificationHandler(
            mock(Configuration.class),
            mock(LifeCycle.class),
            httpState,
            mock(HttpActionHandler.class),
            mock(NettySslContextFactory.class),
            null
        );
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // when - a plain decoder fault (no SSLException cause) is caught
        channel.pipeline().fireExceptionCaught(new DecoderException("could not decode frame"));
        channel.runPendingTasks();

        // then - it is surfaced at WARN rather than silently dropped
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.WARN));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldMarkTheChannelClosedByTheServerForAnUnexpectedExceptionButNotForABenignConnectionClose() {
        HttpState httpState = mock(HttpState.class);
        when(httpState.getMockServerLogger()).thenReturn(mock(MockServerLogger.class));
        EmbeddedChannel unexpected = new EmbeddedChannel(new PortUnificationHandler(mock(Configuration.class), mock(LifeCycle.class), httpState, mock(HttpActionHandler.class), mock(NettySslContextFactory.class), null));
        EmbeddedChannel reset = new EmbeddedChannel(new PortUnificationHandler(mock(Configuration.class), mock(LifeCycle.class), httpState, mock(HttpActionHandler.class), mock(NettySslContextFactory.class), null));

        unexpected.pipeline().fireExceptionCaught(new IllegalStateException("unexpected"));
        reset.pipeline().fireExceptionCaught(new RuntimeException("Connection reset by peer"));

        assertThat(ServerClosedChannels.isClosedByServer(unexpected), is(true));
        assertThat(ServerClosedChannels.isClosedByServer(reset), is(false));
        unexpected.finishAndReleaseAll();
        reset.finishAndReleaseAll();
    }

    @Test
    public void shouldWarnOnceForAClientAddresssFailedHandshakesOnAnyOfTheServersConnectionsAndLogTheRestAtDebug() {
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        HttpState httpState = mock(HttpState.class);
        when(httpState.getMockServerLogger()).thenReturn(logger);
        LifeCycle server = mock(LifeCycle.class);
        when(server.getClientTlsHandshakeFailureLog()).thenReturn(new ClientTlsHandshakeFailureLog());

        for (int connection = 0; connection < 3; connection++) {
            EmbeddedChannel channel = new EmbeddedChannel(new PortUnificationHandler(mock(Configuration.class), server, httpState, mock(HttpActionHandler.class), mock(NettySslContextFactory.class), null));
            // the JDK's engine, and OpenSSL's, whose exception is a subclass
            SSLHandshakeException failure = connection == 1
                ? new SSLHandshakeException("error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN") {
                }
                : new SSLHandshakeException("Received fatal alert: certificate_unknown");
            channel.pipeline().fireExceptionCaught(new DecoderException(failure));
            channel.runPendingTasks();
            assertThat("closes the connection", channel.isOpen(), is(false));
            channel.finishAndReleaseAll();
        }

        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger, times(3)).logEvent(captor.capture());
        assertThat(captor.getAllValues().stream().map(LogEntry::getLogLevel).collect(Collectors.toList()), contains(Level.WARN, Level.DEBUG, Level.DEBUG));
        for (LogEntry entry : captor.getAllValues()) {
            assertThat(entry.getMessageFormat(), startsWith("TLS handshake failed on TCP connection from:{}reason:{}probable cause:{}"));
            assertThat(entry.getArguments()[2], is("the client does not trust MockServer's Certificate Authority"));
        }
    }

    @Test
    public void shouldLogNothingForAClientThatClosedDuringTheHandshake() {
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        HttpState httpState = mock(HttpState.class);
        when(httpState.getMockServerLogger()).thenReturn(logger);
        EmbeddedChannel channel = new EmbeddedChannel(new PortUnificationHandler(mock(Configuration.class), mock(LifeCycle.class), httpState, mock(HttpActionHandler.class), mock(NettySslContextFactory.class), null));

        channel.pipeline().fireExceptionCaught(new DecoderException(new SSLHandshakeException("Received close_notify during handshake")));
        channel.runPendingTasks();

        verify(logger, never()).logEvent(any(LogEntry.class));
        assertThat("closes the connection", channel.isOpen(), is(false));
        channel.finishAndReleaseAll();
    }
}

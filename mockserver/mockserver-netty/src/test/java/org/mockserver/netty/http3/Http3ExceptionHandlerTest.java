package org.mockserver.netty.http3;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http3.DefaultHttp3SettingsFrame;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Exception;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicException;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamResetException;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.codec.quic.QuicTransportError;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.netty.unification.ClientTlsHandshakeFailureLog;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.exception.ExceptionHandling.MAX_FAULT_MESSAGE_LENGTH;
import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;

/**
 * What the last handler of an HTTP/3 connection's pipeline logs for each kind of exception that reaches it, which of
 * them it closes the channel for, that it passes none on to the end of Netty's pipeline, and that it gives each
 * stream Netty's codec keeps for itself a handler that does the same while still passing every stream on.
 */
public class Http3ExceptionHandlerTest {

    private final List<LogEntry> logged = new ArrayList<>();
    private final ClientTlsHandshakeFailureLog clientTlsHandshakeFailureLog = new ClientTlsHandshakeFailureLog();
    private Level logLevel = Level.DEBUG;
    private final MockServerLogger mockServerLogger = new MockServerLogger(Http3ExceptionHandlerTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return isEnabled(level, logLevel);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (isEnabledForInstance(logEntry.getLogLevel())) {
                logged.add(logEntry);
            }
        }
    };

    @Test
    public void shouldLogAClientAddresssFirstFailedTlsHandshakeAsAWarningAndItsLaterOnesAtDebugWithTheirMessageAndNoStackTrace() {
        // as Netty builds them from quiche's TLS and crypto failures
        SSLHandshakeException tlsFailure = new SSLHandshakeException("error:10000133:SSL routines:OPENSSL_internal:NO_APPLICATION_PROTOCOL");
        tlsFailure.initCause(new QuicException("QUICHE_ERR_TLS_FAIL", QuicTransportError.INTERNAL_ERROR));
        Throwable cryptoFailure = new SSLException("x".repeat(MAX_FAULT_MESSAGE_LENGTH + 1));
        Level[] levels = {Level.WARN, Level.DEBUG};
        Throwable[] causes = {tlsFailure, cryptoFailure};
        for (int i = 0; i < causes.length; i++) {
            Throwable cause = causes[i];
            // a connection of its own, from the same address
            EmbeddedChannel connection = connection();
            logged.clear();

            connection.pipeline().fireExceptionCaught(cause);

            assertThat(logged, hasSize(1));
            assertThat(logged.get(0).getLogLevel(), is(levels[i]));
            assertThat(logged.get(0).getMessageFormat(), startsWith("TLS handshake failed on HTTP/3 connection from:{}reason:{}"));
            assertThat(logged.get(0).getArguments()[0], is(connection.remoteAddress()));
            String message = (String) logged.get(0).getArguments()[1];
            assertThat(message.length(), lessThanOrEqualTo(MAX_FAULT_MESSAGE_LENGTH));
            assertThat(cause.getMessage(), startsWith(message.replaceAll("\\.\\.\\.$", "")));
            assertThat(logged.get(0).getThrowable(), is(nullValue()));
            assertHandledWithoutClosing(connection);
        }
    }

    @Test
    public void shouldLogAClientThatClosedTheConnectionWithATlsAlertAsAFailedHandshakeAndPassTheEventOn() throws Exception {
        List<Object> passedOn = new ArrayList<>();
        EmbeddedChannel connection = new EmbeddedChannel(Http3ExceptionHandler.forConnection(mockServerLogger, null, clientTlsHandshakeFailureLog), eventRecorder(passedOn));
        // a CRYPTO_ERROR, 0x100 plus the alert: unknown_ca (48)
        Object unknownCa = closeEvent(false, 0x100 + 48);
        Object noError = closeEvent(false, 0x0);
        Object applicationClose = closeEvent(true, 0x100 + 48);

        connection.pipeline().fireUserEventTriggered(unknownCa);
        connection.pipeline().fireUserEventTriggered(noError);
        connection.pipeline().fireUserEventTriggered(applicationClose);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(0).getMessageFormat(), startsWith("TLS handshake failed on HTTP/3 connection from:{}reason:{}probable cause:{}"));
        assertThat(logged.get(0).getArguments()[2], is("the client does not trust MockServer's Certificate Authority"));
        assertThat(logged.get(0).getArguments()[0], is(connection.remoteAddress()));
        assertThat(logged.get(0).getArguments()[1], is("the client closed the connection with TLS alert 48 (unknown_ca)"));
        assertThat(logged.get(0).getThrowable(), is(nullValue()));
        assertThat(passedOn, contains(sameInstance(unknownCa), sameInstance(noError), sameInstance(applicationClose)));
        assertHandledWithoutClosing(connection);
    }

    private static Object closeEvent(boolean applicationClose, int error) throws Exception {
        Constructor<QuicConnectionCloseEvent> constructor = QuicConnectionCloseEvent.class.getDeclaredConstructor(boolean.class, int.class, byte[].class);
        constructor.setAccessible(true);
        return constructor.newInstance(applicationClose, error, new byte[0]);
    }

    private static ChannelInboundHandlerAdapter eventRecorder(List<Object> passedOn) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                passedOn.add(evt);
            }
        };
    }

    @Test
    public void shouldLogAnHttp3ConnectionErrorAsAWarningWithItsCodeAndCause() {
        Http3Exception unexpectedFrame = new Http3Exception(Http3ErrorCode.H3_FRAME_UNEXPECTED, "Reserved type for HTTP/2 received.");
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(unexpectedFrame);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(0).getMessageFormat(), is("closing HTTP/3 connection from:{}for connection error:{}"));
        assertThat(Arrays.asList(logged.get(0).getArguments()), contains(connection.remoteAddress(), Http3ErrorCode.H3_FRAME_UNEXPECTED));
        assertThat(logged.get(0).getThrowable(), is(sameInstance(unexpectedFrame)));
        // Netty closes the connection with the error code itself, after it fires the exception
        assertHandledWithoutClosing(connection);
    }

    @Test
    public void shouldLogAConnectionItsClientClosedOrResetAtDebugWithoutAStackTrace() {
        // a reset stream is a QuicException, and is not the QUIC error that any other one is
        Exception[] causes = {new IOException("Connection reset by peer"), new ClosedChannelException(), new QuicStreamResetException("STREAM_RESET", Http3ErrorCode.H3_REQUEST_CANCELLED.code())};
        for (Exception cause : causes) {
            EmbeddedChannel connection = connection();
            logged.clear();

            connection.pipeline().fireExceptionCaught(cause);

            assertThat(logged, hasSize(1));
            assertThat(logged.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(logged.get(0).getMessageFormat(), is("HTTP/3 connection from:{}closed or reset by its client:{}"));
            assertThat(logged.get(0).getArguments()[0], is(connection.remoteAddress()));
            assertThat(logged.get(0).getThrowable(), is(nullValue()));
            assertHandledWithoutClosing(connection);
        }
    }

    @Test
    public void shouldLogAnSslOrDecoderFaultAsAWarningWithItsClassAndBoundedMessageAndNotAsAClosedConnection() {
        // as Netty wraps what its frame codec throws; the closed-connection check is false for each of these
        Throwable undecodable = new DecoderException(new IllegalArgumentException("Setting 'HTTP3_SETTINGS_ENABLE_CONNECT_PROTOCOL' invalid"));
        Throwable tooLong = new DecoderException("x".repeat(MAX_FAULT_MESSAGE_LENGTH + 1));
        for (Throwable cause : new Throwable[]{undecodable, tooLong, new RuntimeException(new SSLException("bad record"))}) {
            EmbeddedChannel connection = connection();
            logged.clear();

            connection.pipeline().fireExceptionCaught(cause);

            assertThat(logged, hasSize(1));
            assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
            assertThat(logged.get(0).getMessageFormat(), is("SSL or decoder fault on HTTP/3 connection from:{}:{}:{}"));
            assertThat(Arrays.asList(logged.get(0).getArguments()), contains(connection.remoteAddress(), cause.getClass().getName(), boundedFaultMessage(cause)));
            assertThat(((String) logged.get(0).getArguments()[2]).length(), lessThanOrEqualTo(MAX_FAULT_MESSAGE_LENGTH));
            assertThat(logged.get(0).getThrowable(), is(nullValue()));
            // nothing closed the connection for it before
            assertHandledWithoutClosing(connection);
        }
    }

    @Test
    public void shouldLogAQuicErrorAsAWarningWithItsMessage() {
        QuicException flowControl = new QuicException("FLOW_CONTROL_ERROR: QUICHE_ERR_FLOW_CONTROL", QuicTransportError.FLOW_CONTROL_ERROR);
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(flowControl);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(0).getMessageFormat(), is("QUIC error on HTTP/3 connection from:{}:{}"));
        assertThat(Arrays.asList(logged.get(0).getArguments()), contains(connection.remoteAddress(), "FLOW_CONTROL_ERROR: QUICHE_ERR_FLOW_CONTROL"));
        assertThat(logged.get(0).getThrowable(), is(nullValue()));
        assertHandledWithoutClosing(connection);
    }

    @Test
    public void shouldLogNothingBelowItsLevelAndStillHandleTheException() {
        logLevel = Level.ERROR;
        Throwable[] belowAnError = {
            new IOException("Connection reset by peer"),
            new QuicException("FLOW_CONTROL_ERROR: QUICHE_ERR_FLOW_CONTROL", QuicTransportError.FLOW_CONTROL_ERROR),
            new Http3Exception(Http3ErrorCode.H3_FRAME_UNEXPECTED, "Reserved type for HTTP/2 received."),
            new DecoderException("bad frame")
        };
        for (Throwable cause : belowAnError) {
            EmbeddedChannel connection = connection();

            connection.pipeline().fireExceptionCaught(cause);

            assertThat(logged, empty());
            assertHandledWithoutClosing(connection);
        }
    }

    @Test
    public void shouldLogNettysDirectMemoryLimitAsAnErrorAndCloseTheChannel() throws Exception {
        logLevel = Level.ERROR;
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(new DecoderException(constructor.newInstance("failed to allocate 16777216 byte(s) of direct memory")));

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        assertThat(logged.get(0).getMessageFormat(), startsWith("direct memory limit (io.netty.maxDirectMemory) reached"));
        assertThat(logged.get(0).getThrowable(), is(nullValue()));
        connection.checkException();
        assertThat(connection.isOpen(), is(false));
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldMarkTheConnectionItClosesForTheDirectMemoryLimitAsClosedByTheServerButNotOneItsClientReset() throws Exception {
        logLevel = Level.ERROR;
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        EmbeddedChannel limitReached = connection();
        EmbeddedChannel reset = connection();
        EmbeddedChannel streamConnection = connection();
        EmbeddedChannel stream = new EmbeddedChannel(streamConnection, DefaultChannelId.newInstance(), true, false);
        QuicStreamChannel unidirectional = mock(QuicStreamChannel.class);
        when(unidirectional.type()).thenReturn(QuicStreamType.UNIDIRECTIONAL);
        when(unidirectional.pipeline()).thenReturn(stream.pipeline());
        streamConnection.pipeline().fireChannelRead(unidirectional);

        limitReached.pipeline().fireExceptionCaught(new DecoderException(constructor.newInstance("failed to allocate 16777216 byte(s) of direct memory")));
        stream.pipeline().fireExceptionCaught(new DecoderException(constructor.newInstance("failed to allocate 16777216 byte(s) of direct memory")));
        reset.pipeline().fireExceptionCaught(new QuicStreamResetException("STREAM_RESET", Http3ErrorCode.H3_REQUEST_CANCELLED.code()));

        assertThat(ServerClosedChannels.isClosedByServer(limitReached), is(true));
        assertThat("closing a stream Netty's codec keeps closes its connection", ServerClosedChannels.isClosedByServer(streamConnection), is(true));
        assertThat(ServerClosedChannels.isClosedByServer(reset), is(false));
        limitReached.finishAndReleaseAll();
        stream.finishAndReleaseAll();
        streamConnection.finishAndReleaseAll();
        reset.finishAndReleaseAll();
    }

    @Test
    public void shouldMarkAConnectionClosedThroughItsPipelineAsClosedByTheServerButNotOneClosedWithoutItNorAStream() {
        EmbeddedChannel closedThroughThePipeline = connection();
        EmbeddedChannel closedWithoutThePipeline = connection();
        EmbeddedChannel streamConnection = connection();
        EmbeddedChannel stream = new EmbeddedChannel(streamConnection, DefaultChannelId.newInstance(), true, false);
        QuicStreamChannel unidirectional = mock(QuicStreamChannel.class);
        when(unidirectional.type()).thenReturn(QuicStreamType.UNIDIRECTIONAL);
        when(unidirectional.pipeline()).thenReturn(stream.pipeline());
        streamConnection.pipeline().fireChannelRead(unidirectional);

        // as Netty's codec closes the connection for a connection error, and MockServer for its own reasons
        closedThroughThePipeline.close();
        // as QUIC closes it for a close its client makes, or an idle timeout
        closedWithoutThePipeline.unsafe().close(closedWithoutThePipeline.voidPromise());
        stream.close();

        assertThat(ServerClosedChannels.isClosedByServer(closedThroughThePipeline), is(true));
        assertThat(ServerClosedChannels.isClosedByServer(closedWithoutThePipeline), is(false));
        assertThat("a stream Netty's codec keeps", ServerClosedChannels.isClosedByServer(stream), is(false));
        for (EmbeddedChannel channel : new EmbeddedChannel[]{closedThroughThePipeline, closedWithoutThePipeline, stream, streamConnection}) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldMarkAConnectionWithAQuicTransportErrorAsClosedByTheServerOnceQuicClosesIt() {
        EmbeddedChannel connection = connection();
        EmbeddedChannel stream = new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false);
        EmbeddedChannel reset = connection();
        EmbeddedChannel streamConnection = connection();
        EmbeddedChannel unidirectionalStream = new EmbeddedChannel(streamConnection, DefaultChannelId.newInstance(), true, false);
        QuicStreamChannel unidirectional = mock(QuicStreamChannel.class);
        when(unidirectional.type()).thenReturn(QuicStreamType.UNIDIRECTIONAL);
        when(unidirectional.pipeline()).thenReturn(unidirectionalStream.pipeline());
        streamConnection.pipeline().fireChannelRead(unidirectional);

        // as QUIC fires a transport error it found, before it closes the connection outside the pipeline
        connection.pipeline().fireExceptionCaught(new QuicException("STREAM_LIMIT_ERROR: QUICHE_ERR_STREAM_LIMIT", QuicTransportError.STREAM_LIMIT_ERROR));
        reset.pipeline().fireExceptionCaught(new QuicStreamResetException("STREAM_RESET", Http3ErrorCode.H3_REQUEST_CANCELLED.code()));
        unidirectionalStream.pipeline().fireExceptionCaught(new QuicException("FLOW_CONTROL_ERROR: QUICHE_ERR_FLOW_CONTROL", QuicTransportError.FLOW_CONTROL_ERROR));

        assertThat("while the connection stays open", ServerClosedChannels.isClosedByServer(stream), is(false));
        for (EmbeddedChannel closed : new EmbeddedChannel[]{connection, reset, streamConnection, unidirectionalStream}) {
            closed.unsafe().close(closed.voidPromise());
        }
        assertThat(ServerClosedChannels.isClosedByServer(connection), is(true));
        assertThat(ServerClosedChannels.isClosedByServer(stream), is(true));
        assertThat("a reset stream is no QUIC transport error", ServerClosedChannels.isClosedByServer(reset), is(false));
        assertThat("on a stream Netty's codec keeps", ServerClosedChannels.isClosedByServer(unidirectionalStream), is(false));
        assertThat(ServerClosedChannels.isClosedByServer(streamConnection), is(false));
        for (EmbeddedChannel channel : new EmbeddedChannel[]{connection, stream, reset, streamConnection, unidirectionalStream}) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldMarkAConnectionWithATlsErrorAsClosedByTheServerOnceQuicClosesIt() {
        SSLHandshakeException tlsFailure = new SSLHandshakeException("error:10000412:SSL routines:OPENSSL_internal:SSLV3_ALERT_BAD_CERTIFICATE");
        tlsFailure.initCause(new QuicException("QUICHE_ERR_TLS_FAIL", QuicTransportError.INTERNAL_ERROR));
        EmbeddedChannel tlsFailed = connection();
        EmbeddedChannel cryptoFailed = connection();

        // as QUIC fires them before it closes the connection outside the pipeline
        tlsFailed.pipeline().fireExceptionCaught(tlsFailure);
        cryptoFailed.pipeline().fireExceptionCaught(new SSLException(new QuicException("QUICHE_ERR_CRYPTO_FAIL", QuicTransportError.INTERNAL_ERROR)));
        for (EmbeddedChannel closed : new EmbeddedChannel[]{tlsFailed, cryptoFailed}) {
            closed.unsafe().close(closed.voidPromise());
            assertThat(ServerClosedChannels.isClosedByServer(closed), is(true));
            closed.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotMarkAConnectionItsClientClosesAfterAQuicTransportErrorAsClosedByTheServer() throws Exception {
        EmbeddedChannel connection = connection();
        EmbeddedChannel stream = new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false);
        // an error raised sending, which QUIC does not close the connection for
        connection.pipeline().fireExceptionCaught(new QuicException("PROTOCOL_VIOLATION: QUICHE_ERR_INVALID_STATE", QuicTransportError.PROTOCOL_VIOLATION));

        connection.pipeline().fireUserEventTriggered(closeEvent(true, 0x0));
        connection.unsafe().close(connection.voidPromise());

        assertThat(ServerClosedChannels.isClosedByServer(connection), is(false));
        assertThat(ServerClosedChannels.isClosedByServer(stream), is(false));
        connection.finishAndReleaseAll();
        stream.finishAndReleaseAll();
    }

    @Test
    public void shouldLogAnyOtherExceptionAsAnErrorWithItsCause() {
        logLevel = Level.ERROR;
        IllegalStateException unexpected = new IllegalStateException("unexpected");
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(unexpected);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        assertThat(logged.get(0).getMessageFormat(), startsWith("exception caught on HTTP/3 connection "));
        assertThat(logged.get(0).getThrowable(), is(sameInstance(unexpected)));
        assertHandledWithoutClosing(connection);
    }

    @Test
    public void shouldGiveAStreamNettysCodecKeepsAHandlerOfItsOwnAndPassTheStreamOn() {
        EmbeddedChannel stream = new EmbeddedChannel();
        QuicStreamChannel unidirectional = mock(QuicStreamChannel.class);
        when(unidirectional.type()).thenReturn(QuicStreamType.UNIDIRECTIONAL);
        when(unidirectional.pipeline()).thenReturn(stream.pipeline());
        List<Object> passedOn = new ArrayList<>();
        EmbeddedChannel connection = new EmbeddedChannel(Http3ExceptionHandler.forConnection(mockServerLogger, null, clientTlsHandshakeFailureLog), recorder(passedOn));

        connection.pipeline().fireChannelRead(unidirectional);

        assertThat("where Netty registers the stream", passedOn, contains(sameInstance(unidirectional)));
        stream.pipeline().fireExceptionCaught(new IOException("Connection reset by peer"));
        stream.pipeline().fireExceptionCaught(new Http3Exception(Http3ErrorCode.H3_FRAME_UNEXPECTED, "Reserved type for HTTP/2 received."));
        stream.pipeline().fireExceptionCaught(new DecoderException("bad frame"));
        assertThat(logged, hasSize(3));
        assertThat(logged.get(2).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(2).getMessageFormat(), is("SSL or decoder fault on stream of HTTP/3 connection from:{}:{}:{}"));
        assertThat(logged.get(0).getLogLevel(), is(Level.DEBUG));
        assertThat(logged.get(0).getMessageFormat(), is("stream of HTTP/3 connection from:{}closed or reset by its client:{}"));
        assertThat(logged.get(1).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(1).getMessageFormat(), is("closing HTTP/3 connection from:{}for connection error:{}"));
        assertHandledWithoutClosing(stream);
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldLimitTheBlockedQpackStreamsAClientAllowsToWhatNettysEncoderTakesAndLogItOnceAtDebug() {
        EmbeddedChannel stream = new EmbeddedChannel();
        QuicStreamChannel unidirectional = mock(QuicStreamChannel.class);
        when(unidirectional.type()).thenReturn(QuicStreamType.UNIDIRECTIONAL);
        when(unidirectional.pipeline()).thenReturn(stream.pipeline());
        EmbeddedChannel connection = new EmbeddedChannel(Http3ExceptionHandler.forConnection(mockServerLogger, null, clientTlsHandshakeFailureLog));
        connection.pipeline().fireChannelRead(unidirectional);
        List<Object> passedOn = new ArrayList<>();
        // as Netty's control-stream handler, which configures its QPACK encoder from the SETTINGS
        stream.pipeline().addLast(recorder(passedOn));
        DefaultHttp3SettingsFrame tooMany = new DefaultHttp3SettingsFrame();
        tooMany.settings().qpackBlockedStreams(Integer.MAX_VALUE + 1L).qpackMaxTableCapacity(4096L);
        DefaultHttp3SettingsFrame asMany = new DefaultHttp3SettingsFrame();
        asMany.settings().qpackBlockedStreams(Integer.MAX_VALUE);
        DefaultHttp3SettingsFrame none = new DefaultHttp3SettingsFrame();

        stream.pipeline().fireChannelRead(tooMany);
        stream.pipeline().fireChannelRead(asMany);
        stream.pipeline().fireChannelRead(none);

        assertThat(passedOn, contains(sameInstance(tooMany), sameInstance(asMany), sameInstance(none)));
        assertThat(tooMany.settings().qpackBlockedStreams(), is((long) Integer.MAX_VALUE));
        assertThat("the rest is left as the client sent it", tooMany.settings().qpackMaxTableCapacity(), is(4096L));
        assertThat(asMany.settings().qpackBlockedStreams(), is((long) Integer.MAX_VALUE));
        assertThat(none.settings().qpackBlockedStreams(), is(nullValue()));
        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.DEBUG));
        assertThat(logged.get(0).getMessageFormat(), is("HTTP/3 connection from:{}allows more blocked QPACK streams:{}than MockServer's encoder supports, which blocks at most:{}"));
        assertThat(Arrays.asList(logged.get(0).getArguments()), contains(stream.remoteAddress(), Integer.MAX_VALUE + 1L, Integer.MAX_VALUE));
        assertHandledWithoutClosing(stream);
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldLeaveARequestStreamToItsOwnHandlersAndPassItOn() {
        EmbeddedChannel stream = new EmbeddedChannel();
        QuicStreamChannel bidirectional = mock(QuicStreamChannel.class);
        when(bidirectional.type()).thenReturn(QuicStreamType.BIDIRECTIONAL);
        when(bidirectional.pipeline()).thenReturn(stream.pipeline());
        List<Object> passedOn = new ArrayList<>();
        EmbeddedChannel connection = new EmbeddedChannel(Http3ExceptionHandler.forConnection(mockServerLogger, null, clientTlsHandshakeFailureLog), recorder(passedOn));

        connection.pipeline().fireChannelRead(bidirectional);

        assertThat(passedOn, contains(sameInstance(bidirectional)));
        assertThat("ahead of the request handler MockServer adds when the stream is registered", stream.pipeline().names(), hasSize(1));
        stream.finishAndReleaseAll();
        connection.finishAndReleaseAll();
    }

    private EmbeddedChannel connection() {
        return new EmbeddedChannel(Http3ExceptionHandler.forConnection(mockServerLogger, null, clientTlsHandshakeFailureLog));
    }

    /**
     * An {@link EmbeddedChannel} records what reaches the end of its pipeline and throws it from
     * {@code checkException()}.
     */
    private static void assertHandledWithoutClosing(EmbeddedChannel channel) {
        channel.checkException();
        assertThat("closed, which Netty does itself with the error code", channel.isOpen(), is(true));
        channel.finishAndReleaseAll();
    }

    private static ChannelInboundHandlerAdapter recorder(List<Object> passedOn) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                passedOn.add(msg);
            }
        };
    }
    @Test
    public void shouldReportTheConnectionsPeerForAQuicStream() {
        java.net.InetSocketAddress client = new java.net.InetSocketAddress("127.0.0.1", 54321);
        io.netty.handler.codec.quic.QuicChannel connection = mock(io.netty.handler.codec.quic.QuicChannel.class);
        when(connection.remoteSocketAddress()).thenReturn(client);
        QuicStreamChannel stream = mock(QuicStreamChannel.class);
        when(stream.parent()).thenReturn(connection);

        assertThat(Http3ExceptionHandler.peerAddress(stream), is(client));
        assertThat(Http3ExceptionHandler.peerAddress(connection), is(client));
    }

    @Test
    public void shouldReportNoPeerRatherThanAStreamIdForAQuicStreamWithNoConnection() {
        QuicStreamChannel stream = mock(QuicStreamChannel.class);
        when(stream.remoteAddress()).thenReturn(new io.netty.handler.codec.quic.QuicStreamAddress(4));

        assertThat(Http3ExceptionHandler.peerAddress(stream), nullValue());
        assertThat(Http3ExceptionHandler.peerAddress(null), nullValue());
    }
}
